package com.bitzer.rmainspector.data

/**
 * Pure rules for the queue screens (issue #67; spec §2, §6.6; decision 2026-11-18 "Upload queue and sign-out").
 * No Android classes here, so everything is unit-tested on the JVM.
 */

/** "1 upload" / "3 uploads". */
fun uploadsText(n: Int) = if (n == 1) "1 upload" else "$n uploads"

/** The Home banner after signing in while uploads of this inspector were held (spec §2). */
fun earlierSessionText(n: Int) = "${uploadsText(n)} waiting from your earlier session"

/** What the sign-out prompt shows (spec §2). [count] is the number of uploads waiting. */
sealed interface SignOutPrompt {
    val count: Int

    /** "You have N uploads waiting. Send now before signing out?" — Send now / Sign out anyway. */
    data class Ask(override val count: Int) : SignOutPrompt

    /** Send now is running; the buttons are off until it ends. */
    data class Sending(override val count: Int) : SignOutPrompt

    /** The server can't be reached: says so, offers Try again / Sign out anyway. */
    data class Offline(override val count: Int) : SignOutPrompt

    /** Something else stopped the send ([message] says what); the items stay on the tablet. */
    data class Problem(override val count: Int, val message: String) : SignOutPrompt
}

object SignOutRules {
    /**
     * Sign-out tapped. Null = nothing is waiting, sign out at once (no prompt). [offline] = the app already knows the
     * server can't be reached, so the prompt says so straight away.
     */
    fun begin(items: List<QueueItem>, username: String?, offline: Boolean): SignOutPrompt? {
        val n = QueueRules.waitingFor(items, username).size
        return when {
            n == 0 -> null
            offline -> SignOutPrompt.Offline(n)
            else -> SignOutPrompt.Ask(n)
        }
    }

    /**
     * After Send now ran. Null = every upload of [username] went through: sign out now. Otherwise the prompt to show:
     * offline, or the first reason something is still waiting / newly blocked.
     */
    fun afterSend(before: List<QueueItem>, after: List<QueueItem>, username: String?): SignOutPrompt? {
        val mine = after.filter { it.inspector.username == username }
        val waiting = mine.filter { it.state != QueueState.BLOCKED }
        if (waiting.isNotEmpty()) {
            return if (waiting.any { it.reason == QueueRules.OFFLINE_REASON }) SignOutPrompt.Offline(waiting.size)
            else SignOutPrompt.Problem(waiting.size, waiting.firstNotNullOfOrNull { it.reason } ?: "The upload didn't finish.")
        }
        val blockedBefore = before.filter { it.state == QueueState.BLOCKED }.map { it.id }.toSet()
        val newlyBlocked = mine.filter { it.id !in blockedBefore }
        if (newlyBlocked.isNotEmpty()) {
            return SignOutPrompt.Problem(newlyBlocked.size,
                (newlyBlocked.firstNotNullOfOrNull { it.reason } ?: "It can't be sent.") + " It stays on this tablet.")
        }
        return null
    }

    fun title(p: SignOutPrompt): String = when (p) {
        is SignOutPrompt.Ask -> "You have ${uploadsText(p.count)} waiting."
        is SignOutPrompt.Sending -> "Sending ${uploadsText(p.count)}…"
        is SignOutPrompt.Offline -> "You're offline."
        is SignOutPrompt.Problem -> "Couldn't send everything."
    }

    fun body(p: SignOutPrompt): String = when (p) {
        is SignOutPrompt.Ask -> "Send now before signing out?"
        is SignOutPrompt.Sending -> "Please wait. You are signed out when they are sent."
        is SignOutPrompt.Offline ->
            "${uploadsText(p.count)} can't be sent right now. If you sign out anyway they stay on this tablet and are sent " +
                "when you sign in again."
        is SignOutPrompt.Problem -> "${p.message} Signing out anyway keeps the rest on this tablet until you sign in again."
    }
}

/** One inspector's items in Settings → Upload queue. */
data class QueueGroup(val inspector: DraftInspector, val mine: Boolean, val items: List<QueueItem>)

object QueueGroups {
    /** Items grouped by inspector: the signed-in user's own group first, then the others by name. Queue order inside. */
    fun build(items: List<QueueItem>, username: String?): List<QueueGroup> {
        val byUser = items.groupBy { it.inspector.username }
        return byUser.map { (u, list) -> QueueGroup(list.first().inspector, mine = u == username, items = list) }
            .sortedWith(compareBy<QueueGroup>({ !it.mine }, { it.inspector.displayName.lowercase() }, { it.inspector.username }))
    }

    fun heading(g: QueueGroup): String {
        val who = g.inspector.displayName + if (g.inspector.initials.isNotBlank()) " (${g.inspector.initials})" else ""
        return if (g.mine) "$who — you" else who
    }

    /** What a group header says about the items; other users' items are only counted. */
    fun summary(g: QueueGroup): String = if (g.mine) {
        val blocked = g.items.count { it.state == QueueState.BLOCKED }
        "${uploadsText(g.items.size)}" + if (blocked > 0) ", $blocked blocked" else ""
    } else {
        "${uploadsText(g.items.size)} waiting. Held until ${g.inspector.displayName} signs in."
    }
}

object QueueItemRules {
    fun isMine(item: QueueItem, username: String?) = username != null && item.inspector.username == username

    /** Short state line of an own item. */
    fun status(item: QueueItem): String = when (item.state) {
        QueueState.SENDING -> "Sending…"
        QueueState.WAITING -> "Waiting" + (item.reason?.let { ": $it" } ?: "")
        QueueState.BLOCKED ->
            if (QueueRules.isConflict(item.reasonCode)) "Blocked: changed elsewhere" else "Blocked: ${item.reason ?: "needs a look"}"
    }

    /** Retry now: not while sending; a conflict (`409 RMA_CHANGED`…) is never retried here — it is reviewed in the conflict screen. */
    fun canRetry(item: QueueItem, username: String?) =
        isMine(item, username) && item.state != QueueState.SENDING && !(item.isBlocked && QueueRules.isConflict(item.reasonCode))

    fun canCancel(item: QueueItem, username: String?) = isMine(item, username) && item.state != QueueState.SENDING

    /** What Retry now does to the stored item: back to waiting and due now (a blocked item the inspector chose to retry). */
    fun forRetry(item: QueueItem): QueueItem = item.copy(state = QueueState.WAITING, nextTryAt = 0, reason = null, reasonCode = null)

    /** The error detail text of an own item (empty when there is nothing to say). */
    fun detail(item: QueueItem, now: Long): String = buildList {
        item.reason?.let { add(it) }
        item.reasonCode?.let { add("Code: $it") }
        if (item.attempts > 0) add("Tries so far: ${item.attempts}")
        if (item.state == QueueState.WAITING && item.nextTryAt > now) add("Next try in about ${(item.nextTryAt - now + 999) / 1000} s")
        if (item.inspectionSaved) add("Findings are saved. Photos are still to send.")
    }.joinToString("\n")

    const val CANCEL_TITLE = "Cancel this upload?"
    const val CANCEL_BODY = "It will not be sent. Your findings and photos stay on this tablet; you can submit again from the RMA."
}
