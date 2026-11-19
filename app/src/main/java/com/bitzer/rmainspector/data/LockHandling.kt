package com.bitzer.rmainspector.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The three ways the Macola lock stops a write (spec §6.4, api §1.3). In every one of them nothing was saved. */
enum class LockKind {
    /** `423 RMA_LOCKED`: the RMA is open in Macola by someone else. */
    LOCKED,
    /** `423 RMA_LOCKED` with `possibly_stale`: probably a left-over lock — only IT can clear it. */
    STALE,
    /** `503 LOCK_CHECK_FAILED`: the lock state couldn't be read. Unknown counts as locked (fail closed). */
    CHECK_FAILED,
}

/**
 * What the inspector reads for a lock failure. One wording for the submit result screen and the queue item
 * ([headline] is the one-line form used as the queue item's reason).
 */
data class LockNotice(val kind: LockKind, val headline: String, val title: String, val detail: String)

/**
 * Lock handling that fails closed (spec §6.4, hard rule 3): the app never writes while the lock is held or unknown —
 * the server checks the lock inside the write and refuses, and the app then keeps everything, says why, and tries the
 * same submit (same `submission_id`) again later. Pure functions so the wording and timing are unit-tested.
 */
object LockHandling {
    /** A Macola lock is retried "every few minutes" (spec §6.4): never sooner than this. */
    const val MIN_LOCK_RETRY_MILLIS = 3 * 60_000L

    /** A server hint longer than this is ignored (an inspector shouldn't wait hours on a typo). */
    const val MAX_RETRY_MILLIS = 15 * 60_000L

    fun isLockFailure(f: ApiResult.Failure): Boolean = kindOf(f) != null

    fun kindOf(f: ApiResult.Failure): LockKind? = when {
        f.httpStatus == 423 || f.code == "RMA_LOCKED" ->
            if (f.details?.get("possibly_stale")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) LockKind.STALE else LockKind.LOCKED
        f.code == "LOCK_CHECK_FAILED" -> LockKind.CHECK_FAILED
        else -> null
    }

    private fun text(details: JsonObject?, key: String): String? =
        (details?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** `2026-11-17T10:41:12-05:00` → `2026-11-17 10:41`; anything else is shown as it came. */
    fun sinceText(raw: String): String {
        val m = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})""").find(raw)
        return if (m != null) "${m.groupValues[1]} ${m.groupValues[2]}" else raw
    }

    /** null when [f] is not a lock failure. */
    fun noticeFor(f: ApiResult.Failure): LockNotice? {
        val kind = kindOf(f) ?: return null
        val who = text(f.details, "held_by")
        val host = text(f.details, "host")
        val since = text(f.details, "since")?.let { sinceText(it) }
        val holder = buildString {
            append(who ?: "another user")
            if (host != null) append(" ($host)")
        }
        val kept = "Nothing was saved. ${SubmitErrors.KEPT}"
        return when (kind) {
            LockKind.LOCKED -> LockNotice(
                kind,
                headline = "Open in Macola by $holder${if (since != null) " since $since" else ""} — will retry",
                title = "Open in Macola",
                detail = "$kept It is sent automatically every few minutes once Macola releases it. " +
                    "You can cancel it from the upload queue.",
            )
            LockKind.STALE -> LockNotice(
                kind,
                headline = "Possible stale lock — ask IT",
                title = "Possible stale lock",
                detail = "Macola shows this RMA open by $holder${if (since != null) " since $since" else ""}, " +
                    "but the lock may be left over. $kept Ask IT to clear it. It is tried again every few minutes " +
                    "and you can cancel it from the upload queue.",
            )
            LockKind.CHECK_FAILED -> LockNotice(
                kind,
                headline = "Can't confirm Macola lock — nothing saved, will retry",
                title = "Can't confirm the Macola lock",
                detail = "$kept The app never saves while it can't tell whether the RMA is open in Macola. " +
                    "It tries again automatically. You can cancel it from the upload queue.",
            )
        }
    }

    /**
     * How long to wait before the next try after [attempts] failed tries (1-based): the queue's back-off, raised to the
     * server's `retry_after_seconds` (capped at [MAX_RETRY_MILLIS]); a Macola lock never sooner than
     * [MIN_LOCK_RETRY_MILLIS].
     */
    fun retryDelayMillis(f: ApiResult.Failure, attempts: Int): Long {
        val locked = kindOf(f).let { it == LockKind.LOCKED || it == LockKind.STALE }
        val base = QueueRules.backoffMillis(attempts, locked)
        val hint = (f.retryAfterSeconds ?: 0).coerceIn(0, (MAX_RETRY_MILLIS / 1000).toInt()) * 1000L
        return maxOf(base, hint, if (locked) MIN_LOCK_RETRY_MILLIS else 0L)
    }
}
