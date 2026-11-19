package com.bitzer.rmainspector.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign-out prompt, earlier-session text and queue grouping rules (#67). FAKE data. */
class QueueUiTest {
    private val a = DraftInspector("demo", "Demo Inspector", "DI")
    private val b = DraftInspector("demo2", "Second Inspector", "D2")

    private fun item(id: String, who: DraftInspector = a, rma: String = "912345", state: QueueState = QueueState.WAITING,
                     reason: String? = null, code: String? = null) =
        QueueItem(id = id, rmaId = rma, displayNumber = rma, inspector = who, payload = "{}", state = state, reason = reason, reasonCode = code)

    // ------------------------------------------------------------ sign-out prompt

    @Test fun nothingWaitingSignsOutAtOnce() {
        assertNull(SignOutRules.begin(emptyList(), "demo", offline = false))
        // Only another inspector's items, or only blocked ones, are not "uploads waiting" for this user.
        assertNull(SignOutRules.begin(listOf(item("x", b), item("y", state = QueueState.BLOCKED)), "demo", offline = false))
    }

    @Test fun waitingUploadsAskFirstOnlineAndSayOfflineWhenKnown() {
        val items = listOf(item("1"), item("2", rma = "912346"), item("3", b))
        assertEquals(SignOutPrompt.Ask(2), SignOutRules.begin(items, "demo", offline = false))
        assertEquals(SignOutPrompt.Offline(2), SignOutRules.begin(items, "demo", offline = true))
    }

    @Test fun promptWording() {
        assertEquals("You have 3 uploads waiting.", SignOutRules.title(SignOutPrompt.Ask(3)))
        assertEquals("You have 1 upload waiting.", SignOutRules.title(SignOutPrompt.Ask(1)))
        assertEquals("Send now before signing out?", SignOutRules.body(SignOutPrompt.Ask(3)))
        assertEquals("You're offline.", SignOutRules.title(SignOutPrompt.Offline(2)))
        assertTrue(SignOutRules.body(SignOutPrompt.Offline(2)).startsWith("2 uploads can't be sent right now."))
    }

    @Test fun afterSendEverythingGoneMeansSignOut() {
        val before = listOf(item("1"), item("2", b))
        assertNull(SignOutRules.afterSend(before, listOf(item("2", b)), "demo")) // other user's item doesn't count
    }

    @Test fun afterSendOfflineOffersTryAgainAndSignOutAnyway() {
        val before = listOf(item("1"))
        val after = listOf(item("1", reason = QueueRules.OFFLINE_REASON))
        assertEquals(SignOutPrompt.Offline(1), SignOutRules.afterSend(before, after, "demo"))
    }

    @Test fun afterSendOtherReasonIsShown() {
        val after = listOf(item("1", reason = "Open in Macola. Will retry."))
        assertEquals(SignOutPrompt.Problem(1, "Open in Macola. Will retry."), SignOutRules.afterSend(listOf(item("1")), after, "demo"))
    }

    @Test fun afterSendNewlyBlockedItemIsReportedButOldBlockedIsNot() {
        val oldBlocked = item("old", state = QueueState.BLOCKED, reason = "RMA is closed. Ask the RMA team.")
        val newBlocked = item("new", state = QueueState.BLOCKED, reason = "Changed elsewhere. Review it before sending.", code = "RMA_CHANGED")
        assertNull(SignOutRules.afterSend(listOf(oldBlocked, item("1")), listOf(oldBlocked), "demo"))
        val p = SignOutRules.afterSend(listOf(oldBlocked, item("new")), listOf(oldBlocked, newBlocked), "demo") as SignOutPrompt.Problem
        assertEquals(1, p.count)
        assertTrue(p.message.endsWith("It stays on this tablet."))
    }

    // ------------------------------------------------------------ earlier session banner

    @Test fun earlierSessionText() {
        assertEquals("1 upload waiting from your earlier session", earlierSessionText(1))
        assertEquals("3 uploads waiting from your earlier session", earlierSessionText(3))
    }

    // ------------------------------------------------------------ grouping

    @Test fun groupsOwnFirstThenOthersByName() {
        val c = DraftInspector("zed", "Alex Example", "AE")
        val groups = QueueGroups.build(listOf(item("1", b), item("2", c), item("3"), item("4", b, rma = "912346")), "demo")
        assertEquals(listOf("demo", "zed", "demo2"), groups.map { it.inspector.username })
        assertEquals(listOf(true, false, false), groups.map { it.mine })
        assertEquals(listOf("1", "4"), groups.last().items.map { it.id })
    }

    @Test fun nobodySignedInMeansEveryGroupIsReadOnly() {
        assertTrue(QueueGroups.build(listOf(item("1"), item("2", b)), null).none { it.mine })
    }

    @Test fun otherUsersGroupIsOnlyCountedAndHeld() {
        val g = QueueGroups.build(listOf(item("1", b), item("2", b, rma = "912346")), "demo").single()
        assertEquals("Second Inspector (D2)", QueueGroups.heading(g))
        assertEquals("2 uploads waiting. Held until Second Inspector signs in.", QueueGroups.summary(g))
    }

    @Test fun ownGroupHeadingAndBlockedCount() {
        val g = QueueGroups.build(listOf(item("1"), item("2", state = QueueState.BLOCKED)), "demo").single()
        assertEquals("Demo Inspector (DI) — you", QueueGroups.heading(g))
        assertEquals("2 uploads, 1 blocked", QueueGroups.summary(g))
    }

    // ------------------------------------------------------------ item actions

    @Test fun onlyOwnItemsCanBeRetriedOrCancelled() {
        val mine = item("1"); val theirs = item("2", b)
        assertTrue(QueueItemRules.canRetry(mine, "demo")); assertTrue(QueueItemRules.canCancel(mine, "demo"))
        assertFalse(QueueItemRules.canRetry(theirs, "demo")); assertFalse(QueueItemRules.canCancel(theirs, "demo"))
        assertFalse(QueueItemRules.canRetry(mine, null)); assertFalse(QueueItemRules.canCancel(mine, null))
    }

    @Test fun sendingItemHasNoButtons() {
        val s = item("1", state = QueueState.SENDING)
        assertFalse(QueueItemRules.canRetry(s, "demo")); assertFalse(QueueItemRules.canCancel(s, "demo"))
    }

    @Test fun conflictIsNeverRetriedHereButCanBeCancelled() {
        val c = item("1", state = QueueState.BLOCKED, reason = "Changed elsewhere. Review it before sending.", code = "RMA_CHANGED")
        assertFalse(QueueItemRules.canRetry(c, "demo")); assertTrue(QueueItemRules.canCancel(c, "demo"))
        assertEquals("Blocked: changed elsewhere", QueueItemRules.status(c))
        val closed = item("2", state = QueueState.BLOCKED, reason = "RMA is closed. Ask the RMA team.", code = "RMA_NOT_OPEN")
        assertTrue(QueueItemRules.canRetry(closed, "demo"))
        assertEquals("Blocked: RMA is closed. Ask the RMA team.", QueueItemRules.status(closed))
    }

    @Test fun retryPutsTheItemBackToWaitingAndDueNowWithoutChangingTheInspector() {
        val blocked = item("1", state = QueueState.BLOCKED, reason = "x", code = "RMA_NOT_OPEN").copy(nextTryAt = 99, attempts = 3)
        val r = QueueItemRules.forRetry(blocked)
        assertEquals(QueueState.WAITING, r.state); assertEquals(0L, r.nextTryAt); assertNull(r.reason)
        assertEquals(a, r.inspector); assertEquals(3, r.attempts)
    }

    @Test fun statusAndDetailText() {
        assertEquals("Waiting", QueueItemRules.status(item("1")))
        assertEquals("Waiting: Can't reach the server.", QueueItemRules.status(item("1", reason = QueueRules.OFFLINE_REASON)))
        assertEquals("Sending…", QueueItemRules.status(item("1", state = QueueState.SENDING)))
        val d = item("1", reason = "Open in Macola. Will retry.", code = "RMA_LOCKED").copy(attempts = 2, nextTryAt = 100_000)
        assertEquals("Open in Macola. Will retry.\nCode: RMA_LOCKED\nTries so far: 2\nNext try in about 90 s", QueueItemRules.detail(d, now = 10_000))
        assertEquals("", QueueItemRules.detail(item("1"), 0))
    }
}
