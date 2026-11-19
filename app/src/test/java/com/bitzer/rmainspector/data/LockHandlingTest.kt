package com.bitzer.rmainspector.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Lock handling that fails closed (#66): message selection, retry timing, and the queue retrying a lock. FAKE data. */
class LockHandlingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun details(vararg kv: Pair<String, Any?>) = JsonObject(kv.associate { (k, v) ->
        k to when (v) { null -> kotlinx.serialization.json.JsonNull; is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    })

    private fun locked(by: String? = "JDOE2", host: String? = "EXAMPLE-PC", stale: Boolean = false, since: String? = null, retryAfter: Int? = 120) =
        ApiResult.Failure(ApiResult.Kind.SERVER_ERROR, "RMA 912346 is open in Macola by JDOE2. Nothing was saved.", code = "RMA_LOCKED",
            httpStatus = 423, retryable = true, retryAfterSeconds = retryAfter,
            details = details("held_by" to by, "host" to host, "possibly_stale" to stale, "since" to since))

    private val checkFailed = ApiResult.Failure(ApiResult.Kind.SERVER_ERROR, "Couldn't check the lock.", code = "LOCK_CHECK_FAILED",
        httpStatus = 503, retryable = true, retryAfterSeconds = 60, details = JsonObject(emptyMap()))

    // ------------------------------------------------------------ message selection

    @Test fun lockedNamesTheHolderAndSaysWillRetry() {
        val n = LockHandling.noticeFor(locked())!!
        assertEquals(LockKind.LOCKED, n.kind)
        assertEquals("Open in Macola by JDOE2 (EXAMPLE-PC) — will retry", n.headline)
        assertEquals("Open in Macola", n.title)
        assertTrue(n.detail.contains("Nothing was saved."))
        assertTrue(n.detail.contains(SubmitErrors.KEPT))
    }

    @Test fun lockedShowsSinceWhenTheServerGivesIt() {
        val n = LockHandling.noticeFor(locked(host = null, since = "2026-11-17T10:41:12-05:00"))!!
        assertEquals("Open in Macola by JDOE2 since 2026-11-17 10:41 — will retry", n.headline)
    }

    @Test fun lockedWithoutDetailsStillReads() {
        val f = ApiResult.Failure(ApiResult.Kind.SERVER_ERROR, "x", code = "RMA_LOCKED", httpStatus = 423)
        assertEquals("Open in Macola by another user — will retry", LockHandling.noticeFor(f)!!.headline)
    }

    @Test fun staleLockSaysAskIt() {
        val n = LockHandling.noticeFor(locked(stale = true))!!
        assertEquals(LockKind.STALE, n.kind)
        assertEquals("Possible stale lock — ask IT", n.headline)
        assertTrue(n.detail.contains("Ask IT"))
        assertTrue(n.detail.contains("Nothing was saved."))
    }

    @Test fun lockCheckFailedSaysNothingSavedWillRetry() {
        val n = LockHandling.noticeFor(checkFailed)!!
        assertEquals(LockKind.CHECK_FAILED, n.kind)
        assertEquals("Can't confirm Macola lock — nothing saved, will retry", n.headline)
    }

    @Test fun otherFailuresAreNotLockFailures() {
        assertNull(LockHandling.noticeFor(ApiResult.Failure(ApiResult.Kind.SERVER_ERROR, "x", code = "RMA_CHANGED", httpStatus = 409)))
        assertNull(LockHandling.noticeFor(ApiResult.Failure(ApiResult.Kind.UNREACHABLE, "x")))
        assertFalse(LockHandling.isLockFailure(ApiResult.Failure(ApiResult.Kind.SERVER_ERROR, "x", httpStatus = 503, code = "BACKEND_UNAVAILABLE")))
    }

    @Test fun queueReasonAndSubmitProblemShareTheWording() {
        for (f in listOf(locked(), locked(stale = true), checkFailed)) {
            val n = LockHandling.noticeFor(f)!!
            assertEquals(n.headline, QueueRules.reasonFor(f))
            assertTrue(SubmitErrors.map(f).message.startsWith(n.headline))
            assertEquals(n.title, SubmitErrors.map(f).title)
            assertTrue(QueueableFailure.isQueueable(f))
            assertEquals(QueueDecision.RETRY, QueueRules.decide(f))
        }
    }

    // ------------------------------------------------------------ retry timing

    @Test fun lockIsNeverRetriedSoonerThanThreeMinutes() {
        assertEquals(180_000, LockHandling.retryDelayMillis(locked(retryAfter = null), 1))
        assertEquals(180_000, LockHandling.retryDelayMillis(locked(retryAfter = 30), 1))
        assertEquals(180_000, LockHandling.retryDelayMillis(locked(stale = true, retryAfter = 120), 2))
    }

    @Test fun serverHintLongerThanTheFloorIsHonouredButCapped() {
        assertEquals(600_000, LockHandling.retryDelayMillis(locked(retryAfter = 600), 1))
        assertEquals(900_000, LockHandling.retryDelayMillis(locked(retryAfter = 86_400), 1))
    }

    @Test fun lockCheckFailedFollowsBackoffAndHint() {
        assertEquals(60_000, LockHandling.retryDelayMillis(checkFailed, 1)) // hint 60 s beats 15 s
        assertEquals(60_000, LockHandling.retryDelayMillis(checkFailed.copy(retryAfterSeconds = null), 3))
        assertEquals(900_000, LockHandling.retryDelayMillis(checkFailed.copy(retryAfterSeconds = null), 9))
    }

    // ------------------------------------------------------------ queue: locked → kept → unlocked → sent once

    private class Gateway : QueueGateway {
        var result: () -> ApiResult<InspectionSaved> = { ApiResult.Ok(InspectionSaved(result = "saved", parts = listOf(SavedPart("L1.new")))) }
        val submits = mutableListOf<String>()
        override suspend fun submitInspection(rmaId: String, payload: JsonObject, token: String): ApiResult<InspectionSaved> {
            submits += payload["submission_id"].toString().trim('"'); return result()
        }
        override suspend fun uploadPhoto(rmaId: String, meta: PhotoMetaWrite, file: File, token: String): ApiResult<PhotoUploaded> =
            ApiResult.Ok(PhotoUploaded("x", filename = "f.jpg"))
        override suspend fun rebuildReport(rmaId: String, submissionId: String, token: String) = ApiResult.Ok(ReportRebuilt("ok"))
    }

    private fun runLockScenario(first: ApiResult.Failure, expectedDelay: Long) = runBlocking {
        val who = DraftInspector("demo", "Demo Inspector", "DI")
        val store = UploadQueueStore(File(tmp.root, "queue.json"))
        val gateway = Gateway()
        var clock = 1_000_000L
        val engine = UploadQueueEngine(store, gateway, PhotoStore(File(tmp.root, "photos")), DraftStore(File(tmp.root, "drafts")),
            ReportDraftStore(File(tmp.root, "rd.json")), currentSession = { Session("tok", User("demo", "Demo Inspector", "DI")) }, now = { clock })
        val row = FindingRow("new-1", partCode = Code(null, "04", "Bearing cap"), issueCode = Code(null, "50", "Normal"))
        store.put(QueueItem(id = "s1", rmaId = "912346", displayNumber = "912346", inspector = who,
            payload = """{"submission_id":"s1"}""", rows = listOf(row), draftRows = listOf(row)))

        gateway.result = { first }
        assertEquals(0, engine.drain())
        val waiting = store.get("s1")!!
        assertEquals(QueueState.WAITING, waiting.state)
        assertEquals(LockHandling.noticeFor(first)!!.headline, waiting.reason)
        assertEquals(clock + expectedDelay, waiting.nextTryAt)

        // Not due: no second write attempt, even a minute before the time.
        clock += expectedDelay - 60_000
        engine.drain()
        assertEquals(1, gateway.submits.size)

        // Due, still locked: tried again, kept again.
        clock += 60_000
        engine.drain()
        assertEquals(2, gateway.submits.size)
        assertEquals(2, store.get("s1")!!.attempts)

        // Unlocked: sent once with the same submission id, item gone.
        gateway.result = { ApiResult.Ok(InspectionSaved(result = "saved", parts = listOf(SavedPart("L1.new")))) }
        clock = store.get("s1")!!.nextTryAt
        assertEquals(1, engine.drain())
        assertNull(store.get("s1"))
        assertEquals(listOf("s1", "s1", "s1"), gateway.submits)
        engine.drain()
        assertEquals(3, gateway.submits.size)
    }

    @Test fun lockedItemIsKeptRetriedOnScheduleAndSentOnceAfterUnlock() = runLockScenario(locked(retryAfter = 120), 180_000)

    @Test fun lockCheckFailedItemIsKeptAndRetried() = runLockScenario(checkFailed, 60_000)
}
