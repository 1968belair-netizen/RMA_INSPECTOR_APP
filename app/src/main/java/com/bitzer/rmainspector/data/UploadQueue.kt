package com.bitzer.rmainspector.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** Where a queue item stands (spec §6.6). "Held" is not stored: it is derived from who is signed in. */
@Serializable
enum class QueueState { WAITING, SENDING, BLOCKED }

/**
 * One queued submission (spec §6.4, §6.6; decision 2026-11-18 "Upload queue and sign-out"): the findings request of
 * a submit that could not finish, plus the photos that go with it.
 * - [id] is the `submission_id` of [payload] — a retry keeps it, so the server never writes twice (api §7).
 * - [inspector] is who made it. The item is sent only while **that** user is signed in, with their token; it is
 *   never re-attributed.
 * - [inspectionSaved]: the server already saved the findings (only photos / the report rebuild are left). [rows] are
 *   then the rows with their new `row_id`s; before that they are the rows of the submit.
 * - [draftRows]: the rows of the submit, as the draft held them — the draft is cleared after sending only if it still
 *   holds exactly these (later edits are never thrown away).
 * - [nextTryAt]: epoch milliseconds; 0 = now. [attempts] counts tries that failed on the network or server side.
 */
@Serializable
data class QueueItem(
    val id: String,
    @SerialName("rma_id") val rmaId: String,
    @SerialName("display_number") val displayNumber: String,
    val inspector: DraftInspector,
    /** The `POST /rmas/{id}/inspection` body (api §7) as JSON text. */
    val payload: String,
    val rows: List<FindingRow> = emptyList(),
    @SerialName("draft_rows") val draftRows: List<FindingRow> = emptyList(),
    @SerialName("photo_ids") val photoIds: List<String> = emptyList(),
    @SerialName("inspection_saved") val inspectionSaved: Boolean = false,
    /** Regenerate the report after the findings and photos are in (a submit on a Report Complete / sent RMA). */
    @SerialName("rebuild_report") val rebuildReport: Boolean = false,
    val state: QueueState = QueueState.WAITING,
    /** Why it is waiting or blocked, in words for the inspector. */
    val reason: String? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
    val attempts: Int = 0,
    @SerialName("next_try_at") val nextTryAt: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
) {
    val isBlocked: Boolean get() = state == QueueState.BLOCKED
    val photosLeft: Int get() = photoIds.size
}

/** `<root>/queue.json`: `{format, items}`. */
@Serializable
private data class QueueFile(val format: Int = 1, val items: List<QueueItem> = emptyList())

/**
 * The upload queue on the device (spec §6.6). It lives in app-private storage next to the drafts and photos and is
 * **never cleared by sign-out or a 401** — only by a confirmed send or an explicit cancel. Every change is written
 * to a temp file and renamed over the old one, so a kill mid-write keeps the previous queue. A file that can't be
 * read is copied to `queue.json.bad` (never deleted) and the queue starts empty.
 *
 * An item that was `SENDING` when the app died is put back to `WAITING` on load (the server answers a replay of the
 * same `submission_id` / `photo_id` without writing again).
 */
class UploadQueueStore(private val file: File) {
    private val lock = Any()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    fun snapshot(): List<QueueItem> = _items.value

    fun get(id: String): QueueItem? = _items.value.firstOrNull { it.id == id }

    /** Adds [item]; an item with the same id is replaced (same submission, newer state). */
    fun put(item: QueueItem) = update { list ->
        if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item
    }

    /** Changes the item with [id] (if it is still queued). Returns the new item, or null when it's gone. */
    fun modify(id: String, change: (QueueItem) -> QueueItem): QueueItem? {
        var result: QueueItem? = null
        update { list -> list.map { if (it.id == id) change(it).also { n -> result = n } else it } }
        return result
    }

    fun remove(id: String) = update { list -> list.filter { it.id != id } }

    /**
     * Drops the items of [username] for [rmaId] whose findings the server has **not** saved yet — a newer submit of the
     * same RMA replaces them (the draft is the source of truth). Items already saved stay: their photos still go.
     */
    fun supersede(username: String, rmaId: String) = update { list ->
        list.filterNot { it.inspector.username == username && it.rmaId == rmaId && !it.inspectionSaved }
    }

    private fun update(change: (List<QueueItem>) -> List<QueueItem>) = synchronized(lock) {
        val next = change(_items.value)
        if (next != _items.value) {
            write(next)
            _items.value = next
        }
    }

    private fun write(items: List<QueueItem>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ApiClient.writeJson.encodeToString(QueueFile.serializer(), QueueFile(items = items)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Couldn't save the upload queue." }
        }
    }

    private fun load(): List<QueueItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            ApiClient.defaultJson.decodeFromString(QueueFile.serializer(), file.readText()).items
                .map { if (it.state == QueueState.SENDING) it.copy(state = QueueState.WAITING) else it }
        }.getOrElse {
            runCatching { file.copyTo(File(file.parentFile, file.name + ".bad"), overwrite = true) }
            emptyList()
        }
    }

    /** Removes the whole queue (emulator tests only). */
    fun clearAll() = synchronized(lock) {
        file.delete()
        _items.value = emptyList()
    }
}

/** What the queue does after a failed call. */
enum class QueueDecision {
    /** Try again later with back-off (offline, `423`, `5xx`). */
    RETRY,
    /** Stop and show it to the inspector; **never** retried on its own (`409 RMA_CHANGED`, `422`, …). */
    BLOCK,
    /** The token was rejected (`401`): keep the item, send it after the next sign-in. Not counted as a failed try. */
    HOLD,
}

/** The rules of the queue, pure so they can be unit-tested (spec §6.4, api §1.3). */
object QueueRules {
    private const val MINUTE = 60_000L

    /** Back-off after [attempts] failed tries (1-based): 15 s, 30 s, 1 min, 2 min, 5 min, 10 min, then every 15 min. */
    private val schedule = longArrayOf(15_000, 30_000, MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE)
    private const val MAX_DELAY = 15 * MINUTE

    /** A Macola lock is retried "every few minutes" (spec §6.4): never sooner than this. */
    private const val LOCK_MIN_DELAY = 3 * MINUTE

    fun backoffMillis(attempts: Int, locked: Boolean = false): Long {
        val base = if (attempts <= 0) 0L else schedule.getOrElse(attempts - 1) { MAX_DELAY }
        return if (locked) maxOf(base, LOCK_MIN_DELAY) else base
    }

    fun decide(f: ApiResult.Failure): QueueDecision = when {
        f.kind == ApiResult.Kind.UNREACHABLE -> QueueDecision.RETRY
        f.httpStatus == 401 -> QueueDecision.HOLD
        // Changed elsewhere, closed, …: the inspector looks at it. Only a report file open on a PC is "retry when closed".
        f.httpStatus == 409 -> if (f.code == "REPORT_FILE_IN_USE") QueueDecision.RETRY else QueueDecision.BLOCK
        f.httpStatus == 423 || f.code == "RMA_LOCKED" -> QueueDecision.RETRY
        f.httpStatus != null && f.httpStatus >= 500 -> QueueDecision.RETRY
        f.kind == ApiResult.Kind.BAD_RESPONSE -> QueueDecision.BLOCK
        else -> QueueDecision.BLOCK
    }

    /** The codes that must never be retried automatically and are handled in the conflict screen (B4b). */
    fun isConflict(code: String?): Boolean = code == "RMA_CHANGED" || code == "REPORT_PICS_CHANGED"

    /** The reason text of an item that waits because the server can't be reached (the sign-out prompt looks for it). */
    const val OFFLINE_REASON = "Can't reach the server."

    fun reasonFor(f: ApiResult.Failure): String = when {
        f.kind == ApiResult.Kind.UNREACHABLE -> OFFLINE_REASON
        isConflict(f.code) -> "Changed elsewhere. Review it before sending."
        f.code == "RMA_NOT_OPEN" -> "RMA is closed. Ask the RMA team."
        f.httpStatus == 423 || f.code == "RMA_LOCKED" -> "Open in Macola. Will retry."
        f.code == "LOCK_CHECK_FAILED" -> "Can't confirm the Macola lock. Will retry."
        else -> f.message
    }

    /** Waiting items of [username] that may be tried at [now]; [force] ignores the back-off time (Send now). */
    fun sendable(items: List<QueueItem>, username: String?, now: Long, force: Boolean = false): List<QueueItem> =
        if (username == null) emptyList()
        else items.filter { it.inspector.username == username && it.state == QueueState.WAITING && (force || it.nextTryAt <= now) }

    /** Items of other inspectors (or of nobody signed in): held on the tablet, never sent. */
    fun held(items: List<QueueItem>, username: String?): List<QueueItem> =
        items.filter { it.inspector.username != username }

    /** Items of [username] that are not blocked — what "N uploads waiting" counts. */
    fun waitingFor(items: List<QueueItem>, username: String?): List<QueueItem> =
        items.filter { it.inspector.username == username && it.state != QueueState.BLOCKED }
}

/** Which failures of a submit go into the queue instead of a problem dialog (issue #64: offline and `503`). */
object QueueableFailure {
    fun isQueueable(f: ApiResult.Failure): Boolean =
        f.kind == ApiResult.Kind.UNREACHABLE || f.httpStatus == 503 || f.code == "LOCK_CHECK_FAILED"
}
