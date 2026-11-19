package com.bitzer.rmainspector.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The three server calls a queued submit needs. [token] is the signing-in inspector's own — never another user's. */
interface QueueGateway {
    suspend fun submitInspection(rmaId: String, payload: JsonObject, token: String): ApiResult<InspectionSaved>
    suspend fun uploadPhoto(rmaId: String, meta: PhotoMetaWrite, file: File, token: String): ApiResult<PhotoUploaded>
    suspend fun rebuildReport(rmaId: String, submissionId: String, token: String): ApiResult<ReportRebuilt>
}

/** [QueueGateway] over the real [ApiClient]; every call carries the explicit token it is given. */
class ApiQueueGateway(private val api: ApiClient, private val baseUrl: suspend () -> String) : QueueGateway {
    override suspend fun submitInspection(rmaId: String, payload: JsonObject, token: String) =
        api.submitInspection(baseUrl(), rmaId, payload, token)

    override suspend fun uploadPhoto(rmaId: String, meta: PhotoMetaWrite, file: File, token: String) =
        api.uploadPhoto(baseUrl(), rmaId, meta, file, token)

    override suspend fun rebuildReport(rmaId: String, submissionId: String, token: String) =
        api.rebuildReportAfterSubmit(baseUrl(), rmaId, submissionId, token)
}

/** The server row ids after a save: rows (in order) with their new `row_id`; unchanged when the counts differ. */
fun remapSavedRows(rows: List<FindingRow>, saved: InspectionSaved): List<FindingRow> =
    if (saved.parts.size == rows.size) rows.zip(saved.parts) { row, p -> row.copy(localId = p.rowId, rowId = p.rowId) } else rows

/**
 * Sends the queue (spec §6.4, §6.6). One run at a time (a [Mutex]), so an item is never sent twice at once.
 * Rules:
 * - Only items of the **signed-in** user are sent, with that user's token; everything else stays held.
 * - A `409` (RMA_CHANGED, REPORT_PICS_CHANGED, …) blocks the item; it is never retried on its own.
 * - Offline / `423` / `5xx` → back-off ([QueueRules.backoffMillis]); `401` → the item waits for the next sign-in.
 * - Retries reuse the item's `submission_id` and the photos' `photo_id`, so nothing is written twice.
 * - After the server confirms: sent photos move to the 30-day "sent" area, the draft clears (only if unchanged),
 *   the item leaves the queue.
 */
class UploadQueueEngine(
    private val store: UploadQueueStore,
    private val gateway: QueueGateway,
    private val photos: PhotoStore,
    private val drafts: DraftStore,
    private val reportDrafts: ReportDraftStore,
    private val currentSession: () -> Session?,
    private val onUnauthorized: (rejectedToken: String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /**
     * Sends what is due for the signed-in user. [force] ignores the back-off time (Retry now / Send now); [onlyId]
     * limits it to one item. Returns how many items were fully sent.
     */
    suspend fun drain(force: Boolean = false, onlyId: String? = null): Int = mutex.withLock {
        val session = currentSession() ?: return@withLock 0
        var sent = 0
        val due = QueueRules.sendable(store.snapshot(), session.user.username, now(), force)
            .filter { onlyId == null || it.id == onlyId }
        for (item in due) {
            // The user may have signed out or changed meanwhile: stop, nothing is sent under another token.
            if (currentSession()?.token != session.token) break
            if (sendOne(item.id, session)) sent++
        }
        sent
    }

    private fun stillSignedIn(session: Session) = currentSession()?.token == session.token

    /** Puts the item back to waiting without counting a failed try (sign-out mid-send). */
    private fun release(id: String) { store.modify(id) { it.copy(state = QueueState.WAITING) } }

    private suspend fun sendOne(id: String, session: Session): Boolean {
        var item = store.modify(id) { it.copy(state = QueueState.SENDING, reason = null) } ?: return false
        val token = session.token

        if (!item.inspectionSaved) {
            val payload = runCatching { Json.parseToJsonElement(item.payload).jsonObject }.getOrNull()
                ?: run { store.modify(id) { it.copy(state = QueueState.BLOCKED, reason = "The saved request can't be read.") }; return false }
            when (val r = gateway.submitInspection(item.rmaId, payload, token)) {
                is ApiResult.Failure -> { fail(id, r, session); return false }
                is ApiResult.Ok -> {
                    val saved = r.value
                    val rows = remapSavedRows(item.rows, saved)
                    runCatching {
                        if (saved.parts.size == item.rows.size) {
                            photos.remapRows(item.inspector.username, item.rmaId, item.rows.zip(saved.parts).associate { (row, p) -> row.localId to p.rowId })
                        }
                        // Clear the draft only if the inspector hasn't edited it since the submit.
                        val draft = drafts.read(item.inspector.username, item.rmaId)
                        if (draft != null && draft.rows == item.draftRows) drafts.delete(item.inspector.username, item.rmaId)
                        reportDrafts.get(item.rmaId)?.let { if (it.fieldsDirty) reportDrafts.put(it.copy(fieldsDirty = false)) }
                    }
                    item = store.modify(id) { it.copy(inspectionSaved = true, rows = rows) } ?: return false
                }
            }
        }

        // Photos, in capture order. A photo the server refuses stays on the tablet; the rest are still sent.
        val pending = photos.pending(item.inspector.username, item.rmaId).filter { it.photoId in item.photoIds }
        for (photo in pending) {
            if (!stillSignedIn(session)) { release(id); return false }
            val meta = PhotoUpload.meta(photo, item.rows)
            val file = photos.file(item.inspector.username, item.rmaId, photo)
            if (meta == null || !file.exists()) { dropPhoto(id, photo.photoId); continue }
            when (val r = gateway.uploadPhoto(item.rmaId, meta, file, token)) {
                is ApiResult.Ok -> {
                    photos.markSent(item.inspector.username, item.rmaId, photo.photoId, r.value.filename)
                    dropPhoto(id, photo.photoId)
                }
                is ApiResult.Failure -> when (QueueRules.decide(r)) {
                    QueueDecision.BLOCK -> if (QueueRules.isConflict(r.code)) { fail(id, r, session); return false } else dropPhoto(id, photo.photoId)
                    else -> { fail(id, r, session); return false }
                }
            }
        }
        // Photos that were sent already (or are gone) no longer count.
        store.modify(id) { it.copy(photoIds = it.photoIds.filter { pid -> photos.pending(it.inspector.username, it.rmaId).any { p -> p.photoId == pid } }) }

        item = store.get(id) ?: return false
        if (item.rebuildReport) {
            if (!stillSignedIn(session)) { release(id); return false }
            when (val r = gateway.rebuildReport(item.rmaId, item.id, token)) {
                is ApiResult.Failure -> if (QueueRules.decide(r) != QueueDecision.BLOCK) { fail(id, r, session); return false }
                is ApiResult.Ok -> Unit
            }
        }
        store.remove(id)
        return true
    }

    /** The photo no longer belongs to this item (sent, refused or can't be filed): it stays on the tablet as it is. */
    private fun dropPhoto(id: String, photoId: String) { store.modify(id) { it.copy(photoIds = it.photoIds - photoId) } }

    private fun fail(id: String, f: ApiResult.Failure, session: Session) {
        when (QueueRules.decide(f)) {
            QueueDecision.RETRY -> store.modify(id) {
                val n = it.attempts + 1
                it.copy(state = QueueState.WAITING, attempts = n, reason = QueueRules.reasonFor(f), reasonCode = f.code,
                    nextTryAt = now() + QueueRules.backoffMillis(n, locked = f.httpStatus == 423 || f.code == "RMA_LOCKED"))
            }
            QueueDecision.HOLD -> {
                store.modify(id) { it.copy(state = QueueState.WAITING, reason = "Sign in again to send this.", reasonCode = f.code) }
                onUnauthorized(session.token)
            }
            QueueDecision.BLOCK -> store.modify(id) {
                it.copy(state = QueueState.BLOCKED, reason = QueueRules.reasonFor(f), reasonCode = f.code)
            }
        }
    }
}

/**
 * Runs [UploadQueueEngine.drain] in the background: every [TICK_MS], and at once when [kick] is called (sign-in).
 * With an empty queue a tick does nothing — no network.
 */
class UploadQueueRunner(private val engine: UploadQueueEngine) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun kick() { kicks.trySend(Unit) }

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { engine.drain() }
                withTimeoutOrNull(TICK_MS) { kicks.receive() }
            }
        }
    }

    companion object { const val TICK_MS = 10_000L }
}
