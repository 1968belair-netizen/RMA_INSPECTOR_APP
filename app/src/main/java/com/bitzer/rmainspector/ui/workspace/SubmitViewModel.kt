package com.bitzer.rmainspector.ui.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.Draft
import com.bitzer.rmainspector.data.conflictCurrent
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.data.LocalPhoto
import com.bitzer.rmainspector.data.LockHandling
import com.bitzer.rmainspector.data.PhotoUpload
import com.bitzer.rmainspector.data.QueueItem
import com.bitzer.rmainspector.data.QueueRules
import com.bitzer.rmainspector.data.QueueableFailure
import com.bitzer.rmainspector.data.toDraftInspector
import com.bitzer.rmainspector.data.PhotoOutcome
import com.bitzer.rmainspector.data.PhotoUploadRunner
import com.bitzer.rmainspector.data.PhotoUploadSummary
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.data.SubmitErrors
import com.bitzer.rmainspector.data.SubmitInput
import com.bitzer.rmainspector.data.SubmitKind
import com.bitzer.rmainspector.data.SubmitPayload
import com.bitzer.rmainspector.data.SubmitProblem
import com.bitzer.rmainspector.data.SubmitReview
import com.bitzer.rmainspector.data.rebuildReportAfterSubmit
import com.bitzer.rmainspector.data.submitInspection
import com.bitzer.rmainspector.data.uploadPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Submit inspection (spec §6.1, api §7–8): review → save parts + summary + status → upload the unsent photos → done.
 * The workspace hands over a [SubmitInput] snapshot with [open]; nothing is sent before the inspector taps SUBMIT.
 * If the server can't be reached (or answers 503) the submit goes into the upload queue (spec §6.4, [UploadQueueEngine]);
 * any other failure keeps the draft and the photos on the tablet and says why.
 */
class SubmitViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val rmaId: String = checkNotNull(saved.get<String>(WorkspaceViewModel.ARG_RMA_ID))
    private val api = ServiceLocator.api
    private val settings = ServiceLocator.settings(app)
    private val drafts = ServiceLocator.drafts(app)
    private val photoStore = ServiceLocator.photos(app)
    private val reportDrafts = ServiceLocator.reportDrafts(app)
    private val queue = ServiceLocator.uploadQueue(app)
    private val auth = ServiceLocator.auth(app)

    private val _phase = MutableStateFlow<InspectionSubmitPhase>(InspectionSubmitPhase.Closed)
    val phase: StateFlow<InspectionSubmitPhase> = _phase.asStateFlow()

    private val _refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Emits when the RMA changed on the server (or photos were sent): the workspace reloads. */
    val refresh: SharedFlow<Unit> = _refresh.asSharedFlow()

    private val _conflicts = MutableSharedFlow<ConflictRequest>(extraBufferCapacity = 2)

    /** `409 RMA_CHANGED`: the conflict screen opens for this request (spec §6.5). Never retried automatically. */
    val conflicts: SharedFlow<ConflictRequest> = _conflicts.asSharedFlow()

    private var input: SubmitInput? = null
    private var attempt: Pair<String, String>? = null // (submission_id, fingerprint) of the last failed attempt

    /** Submit tapped: shows the review screen for [snapshot] (null = the RMA isn't loaded yet → nothing happens). */
    fun open(snapshot: SubmitInput?) {
        if (snapshot == null || _phase.value != InspectionSubmitPhase.Closed) return
        input = snapshot
        _phase.value = InspectionSubmitPhase.Review(SubmitReview.build(snapshot))
    }

    fun cancel() { if (_phase.value is InspectionSubmitPhase.Review) _phase.value = InspectionSubmitPhase.Closed }

    /** SUBMIT on the review screen. Report Complete / sent RMAs ask first. */
    fun submit() {
        val r = (_phase.value as? InspectionSubmitPhase.Review)?.review ?: return
        if (!r.canSubmit) return
        if (r.plan.rebuildsReport) _phase.value = InspectionSubmitPhase.Review(r, warning = true) else run(r)
    }

    fun confirmWarning() {
        val r = (_phase.value as? InspectionSubmitPhase.Review)?.review ?: return
        run(r)
    }

    fun dismissWarning() {
        val s = _phase.value as? InspectionSubmitPhase.Review ?: return
        _phase.value = s.copy(warning = false)
    }

    /** Closes the problem dialog or the done screen. */
    fun close() { _phase.value = InspectionSubmitPhase.Closed }

    private fun run(review: SubmitReview) {
        val inp = input ?: return
        val plan = review.plan
        _phase.value = InspectionSubmitPhase.Working("Saving findings…")
        viewModelScope.launch {
            val base = settings.settings.first().serverUrl
            val payloadFor = { id: String -> SubmitPayload.build(inp, plan, id, Draft.nowIso()) }
            val fingerprint = SubmitPayload.fingerprint(payloadFor(""))
            // A retry of the same submit keeps its id, so a save the server did but we never heard about isn't repeated.
            val id = attempt?.takeIf { it.second == fingerprint }?.first ?: UUID.randomUUID().toString()
            attempt = id to fingerprint

            val saved = when (val r = api.submitInspection(base, rmaId, payloadFor(id))) {
                is ApiResult.Failure -> {
                    if (r.code == "RMA_CHANGED") {
                        attempt = null
                        val who = auth.session.value?.user
                        if (who != null && who.username == inp.username) {
                            _phase.value = InspectionSubmitPhase.Closed
                            _conflicts.tryEmit(ConflictRequest(inp.base, conflictCurrent(r.details), inp.rows, inp.report, who.toDraftInspector(), inp.codes))
                            return@launch
                        }
                    }
                    // Fail closed (#66): a Macola lock / unconfirmed lock keeps everything and retries the same submit later.
                    if (QueueableFailure.isQueueable(r) && enqueue(inp, review, payloadFor(id), id, saved = false, rows = inp.rows,
                            photos = inp.pendingPhotos, offline = r.kind == ApiResult.Kind.UNREACHABLE, reason = QueueRules.reasonFor(r),
                            failure = r)) return@launch
                    fail(SubmitErrors.map(r)); return@launch
                }
                is ApiResult.Ok -> r.value
            }
            attempt = null
            runCatching { queue.supersede(inp.username, rmaId) } // an older queued copy of this submit is stale now

            // The server gave the rows new ids: the rows and their photos follow them (api §7).
            val rows = if (saved.parts.size == inp.rows.size)
                inp.rows.zip(saved.parts) { row, p -> row.copy(localId = p.rowId, rowId = p.rowId) }
            else inp.rows
            val photos = withContext(Dispatchers.IO) {
                runCatching {
                    if (saved.parts.size == inp.rows.size) {
                        photoStore.remapRows(inp.username, rmaId, inp.rows.zip(saved.parts).associate { (row, p) -> row.localId to p.rowId })
                    }
                    drafts.delete(inp.username, rmaId)
                    reportDrafts.get(rmaId)?.let { if (it.fieldsDirty) reportDrafts.put(it.copy(fieldsDirty = false)) }
                    photoStore.pending(inp.username, rmaId)
                }.getOrDefault(emptyList())
            }
            _refresh.emit(Unit)

            val upload: PhotoUploadSummary = if (photos.isEmpty()) PhotoUploadSummary(0, emptyList(), 0, false) else {
                _phase.value = InspectionSubmitPhase.Working("Uploading photos…", 0, photos.size)
                withContext(Dispatchers.IO) {
                    PhotoUploadRunner.run(
                        photos, rows,
                        upload = { meta, photo ->
                            val file = photoStore.file(inp.username, rmaId, photo)
                            if (!file.exists()) PhotoOutcome.Rejected("A photo file is missing on this tablet.")
                            else when (val r = api.uploadPhoto(base, rmaId, meta, file)) {
                                is ApiResult.Ok -> PhotoOutcome.Sent(r.value.filename)
                                is ApiResult.Failure ->
                                    if (r.kind == ApiResult.Kind.UNREACHABLE) PhotoOutcome.Offline(r.message) else PhotoOutcome.Rejected(r.message)
                            }
                        },
                        markSent = { photo, name -> photoStore.markSent(inp.username, rmaId, photo.photoId, name) },
                        onProgress = { done, total -> _phase.value = InspectionSubmitPhase.Working("Uploading photos…", done, total) },
                    )
                }
            }

            if (upload.stoppedOffline) {
                // The findings are saved; the photos (and the report rebuild) wait in the queue.
                val left = photoStore.pending(inp.username, rmaId)
                if (enqueue(inp, review, payloadFor(id), id, saved = true, rows = rows, photos = left, offline = true, reason = "Can't reach the server.")) return@launch
            }

            var rebuilt: Boolean? = null
            var rebuildProblem: String? = null
            if (plan.rebuildsReport) {
                _phase.value = InspectionSubmitPhase.Working("Rebuilding the report…")
                when (val r = api.rebuildReportAfterSubmit(base, rmaId, UUID.randomUUID().toString())) {
                    is ApiResult.Ok -> rebuilt = true
                    is ApiResult.Failure -> { rebuilt = false; rebuildProblem = r.message }
                }
            }
            _refresh.emit(Unit)
            _phase.value = InspectionSubmitPhase.Done(
                SubmitDone(
                    status = plan.resultingStatus,
                    statusChanged = plan.kind == SubmitKind.SET_INSPECTED,
                    partsSaved = review.partsChanged,
                    photosSent = upload.sent,
                    photoProblems = upload.failed.distinct(),
                    photosKept = upload.skipped,
                    reportRebuilt = rebuilt,
                    reportProblem = rebuildProblem,
                    warnings = saved.warnings.map { it.message.ifBlank { it.code } },
                ),
            )
        }
    }

    /**
     * Stores the submit in the upload queue under the signed-in inspector's name. False = it couldn't be queued
     * (nobody signed in, or the disk write failed) — the caller then shows the normal problem dialog.
     */
    private fun enqueue(
        inp: SubmitInput, review: SubmitReview, payload: kotlinx.serialization.json.JsonObject, id: String,
        saved: Boolean, rows: List<FindingRow>, photos: List<LocalPhoto>, offline: Boolean, reason: String,
        failure: ApiResult.Failure? = null,
    ): Boolean {
        val user = auth.session.value?.user ?: return false
        if (user.username != inp.username) return false
        val ids = photos.filter { PhotoUpload.meta(it, rows) != null }.map { it.photoId }
        val ok = runCatching {
            queue.supersede(user.username, rmaId)
            queue.put(
                QueueItem(
                    id = id, rmaId = rmaId, displayNumber = inp.rma.displayNumber, inspector = user.toDraftInspector(),
                    payload = payload.toString(), rows = rows, draftRows = inp.rows, photoIds = ids,
                    inspectionSaved = saved, rebuildReport = review.plan.rebuildsReport,
                    reason = reason, reasonCode = failure?.code, attempts = 1,
                    nextTryAt = System.currentTimeMillis() +
                        (failure?.let { LockHandling.retryDelayMillis(it, 1) } ?: QueueRules.backoffMillis(1)),
                    createdAt = Draft.nowIso(),
                ),
            )
        }.isSuccess
        if (!ok) return false
        attempt = null
        _phase.value = InspectionSubmitPhase.Queued(SubmitQueued(findingsSaved = saved, photosQueued = ids.size, offline = offline,
            lock = failure?.let { LockHandling.noticeFor(it) }))
        return true
    }

    private fun fail(p: SubmitProblem) { _phase.value = InspectionSubmitPhase.Failed(p) }
}
