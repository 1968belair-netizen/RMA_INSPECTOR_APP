package com.bitzer.rmainspector.ui.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitzer.rmainspector.data.ApiResult
import com.bitzer.rmainspector.data.CodeLists
import com.bitzer.rmainspector.data.ConflictReapply
import com.bitzer.rmainspector.data.ConflictReview
import com.bitzer.rmainspector.data.ConflictSummary
import com.bitzer.rmainspector.data.Draft
import com.bitzer.rmainspector.data.DraftInspector
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.data.FindingsEdit
import com.bitzer.rmainspector.data.Pick
import com.bitzer.rmainspector.data.PhotoOutcome
import com.bitzer.rmainspector.data.PhotoUploadRunner
import com.bitzer.rmainspector.data.ReportDraft
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.data.toFindingRows
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

/**
 * What the conflict screen needs from the caller: the RMA as the inspector loaded it ([base]), what the server has now
 * ([current]; null = fetch it), and the inspector's own edits.
 */
data class ConflictRequest(
    val base: RmaDetail,
    val current: RmaDetail?,
    val rows: List<FindingRow>,
    val report: ReportDraft?,
    val inspector: DraftInspector,
    val codes: CodeLists?,
)

/**
 * Conflict screen (spec §6.5): shows everything changed elsewhere next to the inspector's edits, and applies the
 * inspector's choice. Nothing is merged or sent automatically, and nothing on the server is written here — only
 * the tablet's own draft, report draft, photo links and queue change. The choices:
 * - [keepMine]: reload, re-apply my edits on top, back to Review (a both-sides summary field needs an explicit pick);
 * - [discard]: drop my edits, reload (photos stay);
 * - [sendPhotosOnly]: file the photos, never touching the record.
 */
class ConflictViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    val rmaId: String = checkNotNull(saved.get<String>(WorkspaceViewModel.ARG_RMA_ID))
    private val api = ServiceLocator.api
    private val settings = ServiceLocator.settings(app)
    private val drafts = ServiceLocator.drafts(app)
    private val reportDrafts = ServiceLocator.reportDrafts(app)
    private val photoStore = ServiceLocator.photos(app)
    private val queue = ServiceLocator.uploadQueue(app)

    private val _phase = MutableStateFlow<ConflictPhase>(ConflictPhase.Closed)
    val phase: StateFlow<ConflictPhase> = _phase.asStateFlow()

    private val _outcomes = MutableSharedFlow<ConflictOutcome>(extraBufferCapacity = 4)
    val outcomes: SharedFlow<ConflictOutcome> = _outcomes.asSharedFlow()

    private var request: ConflictRequest? = null
    private var current: RmaDetail? = null

    fun open(req: ConflictRequest) {
        if (_phase.value != ConflictPhase.Closed) return
        request = req
        val cur = req.current
        if (cur != null) { show(cur); return }
        _phase.value = ConflictPhase.Loading
        viewModelScope.launch {
            when (val r = api.rmaDetail(settings.settings.first().serverUrl, rmaId)) {
                is ApiResult.Ok -> show(r.value)
                is ApiResult.Failure -> _phase.value = ConflictPhase.Failed(r.message)
            }
        }
    }

    private fun show(cur: RmaDetail) {
        val req = request ?: return
        current = cur
        val pending = runCatching { photoStore.pending(req.inspector.username, rmaId) }.getOrDefault(emptyList())
        val blocks = req.codes?.summaryBlocks.orEmpty()
        val review = ConflictReview.build(
            req.base, cur, req.rows, req.report, pending.size,
            blockLabels = blocks.associate { it.blockId to it.label }, blockLimits = blocks.associate { it.blockId to it.maxLength },
        )
        _phase.value = ConflictPhase.Open(ConflictUiState(review))
    }

    private fun update(change: (ConflictUiState) -> ConflictUiState) =
        _phase.update2 { p: ConflictPhase -> if (p is ConflictPhase.Open) p.copy(ui = change(p.ui)) else p }

    private fun ui(): ConflictUiState? = (_phase.value as? ConflictPhase.Open)?.ui

    fun pick(key: String, pick: Pick) = update { it.copy(picks = it.picks + (key to pick), problem = null) }

    fun askDiscard() = update { if (it.busy == null) it.copy(confirmDiscard = true) else it }
    fun cancelDiscard() = update { it.copy(confirmDiscard = false) }

    /** "Decide later": nothing changes; the draft stays and the banner offers the review again. */
    fun later() {
        if (ui()?.busy != null) return
        _phase.value = ConflictPhase.Closed
        request = null; current = null
    }

    fun closeFailed() { _phase.value = ConflictPhase.Closed; request = null }

    fun keepMine() {
        val st = ui() ?: return
        val req = request ?: return
        val cur = current ?: return
        if (!st.canKeepMine) return
        update { it.copy(busy = "Re-applying your changes…", problem = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val user = req.inspector.username
                    val re = st.review.reapply
                    req.report?.let { rep ->
                        val next = if (rep.fieldsDirty) ConflictSummary.merged(cur, rep, st.review.summary, st.picks)
                        else ReportDraft.refresh(rep, cur, null, req.inspector.initials).draft
                        reportDrafts.put(next)
                    }
                    photoStore.remapRows(user, rmaId, re.idMap.filter { (k, v) -> k != v })
                    if (FindingsEdit.isUnchanged(re.rows, cur.parts)) drafts.delete(user, rmaId)
                    else drafts.write(Draft.start(cur, req.inspector, re.rows))
                    // A queued copy of the old submit is stale now: the new Review sends the re-applied edits.
                    queue.supersede(user, rmaId)
                }
            }
            finish(result, openReview = true)
        }
    }

    fun discard() {
        val st = ui() ?: return
        val req = request ?: return
        val cur = current ?: return
        update { it.copy(confirmDiscard = false, busy = "Reloading the RMA…", problem = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val user = req.inspector.username
                    // My photos follow the rows they were taken for (matched by the original values); photos of rows I added stay unlinked.
                    val re = ConflictReapply.rows(req.base.parts, cur.parts, req.base.parts.toFindingRows())
                    photoStore.remapRows(user, rmaId, re.idMap.filter { (k, v) -> k != v })
                    drafts.delete(user, rmaId)
                    reportDrafts.get(rmaId)?.let { reportDrafts.put(ReportDraft.refresh(it.copy(fieldsDirty = false), cur, null, req.inspector.initials).draft) }
                    queue.supersede(user, rmaId)
                }
            }
            finish(result, openReview = false)
        }
    }

    private fun finish(result: Result<Unit>, openReview: Boolean) {
        if (result.isSuccess) {
            _phase.value = ConflictPhase.Closed
            request = null; current = null
            _outcomes.tryEmit(ConflictOutcome.Resolved(openReview))
        } else {
            update { it.copy(busy = null, problem = "Couldn't update the tablet's copy. Nothing was changed on the server. Try again.") }
        }
    }

    /** Files the photos without touching the record (names use the tablet's row order, spec §6.3). */
    fun sendPhotosOnly() {
        val st = ui() ?: return
        val req = request ?: return
        if (st.busy != null) return
        update { it.copy(busy = "Sending photos…", message = null, problem = null) }
        viewModelScope.launch {
            val base = settings.settings.first().serverUrl
            val user = req.inspector.username
            val summary = withContext(Dispatchers.IO) {
                val photos = photoStore.pending(user, rmaId)
                PhotoUploadRunner.run(
                    photos, req.rows,
                    upload = { meta, photo ->
                        val file = photoStore.file(user, rmaId, photo)
                        if (!file.exists()) PhotoOutcome.Rejected("A photo file is missing on this tablet.")
                        else when (val r = api.uploadPhoto(base, rmaId, meta, file)) {
                            is ApiResult.Ok -> PhotoOutcome.Sent(r.value.filename)
                            is ApiResult.Failure ->
                                if (r.kind == ApiResult.Kind.UNREACHABLE) PhotoOutcome.Offline(r.message) else PhotoOutcome.Rejected(r.message)
                        }
                    },
                    markSent = { photo, name -> photoStore.markSent(user, rmaId, photo.photoId, name) },
                )
            }
            val left = runCatching { photoStore.pending(user, rmaId).size }.getOrDefault(0)
            val text = buildString {
                append("${summary.sent} photo${if (summary.sent == 1) "" else "s"} filed. The record was not touched.")
                if (summary.failed.isNotEmpty() || summary.skipped > 0) append(" Some photos stay on this tablet.")
            }
            update { s ->
                s.copy(busy = null, message = text, review = s.review.copy(photosPending = left),
                    problem = if (summary.stoppedOffline) "Can't reach the server. The remaining photos stay on this tablet." else null)
            }
        }
    }
}

private inline fun <T> MutableStateFlow<T>.update2(change: (T) -> T) { value = change(value) }
