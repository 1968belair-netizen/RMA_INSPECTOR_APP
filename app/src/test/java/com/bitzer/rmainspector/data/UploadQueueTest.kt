package com.bitzer.rmainspector.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Upload queue: persistence, back-off, held-vs-sendable, never auto-retrying 409, idempotent send (#64). FAKE data. */
class UploadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = DraftInspector("demo", "Demo Inspector", "DI")
    private val b = DraftInspector("demo2", "Second Inspector", "D2")
    private fun session(u: DraftInspector, token: String = "tok-${u.username}") = Session(token, User(u.username, u.displayName, u.initials))

    private fun item(id: String = "s1", who: DraftInspector = a, rma: String = "912345", photoIds: List<String> = emptyList(), saved: Boolean = false) =
        QueueItem(id = id, rmaId = rma, displayNumber = rma, inspector = who, payload = """{"submission_id":"$id"}""",
            rows = listOf(FindingRow("new-1", partCode = Code(null, "04", "Bearing cap"), issueCode = Code(null, "50", "Normal"))),
            draftRows = listOf(FindingRow("new-1", partCode = Code(null, "04", "Bearing cap"), issueCode = Code(null, "50", "Normal"))),
            photoIds = photoIds, inspectionSaved = saved)

    private fun fail(kind: ApiResult.Kind = ApiResult.Kind.SERVER_ERROR, status: Int? = null, code: String? = null) =
        ApiResult.Failure(kind, "msg", code = code, httpStatus = status)

    // ------------------------------------------------------------ store

    @Test fun addPersistAndRestore() {
        val f = File(tmp.root, "queue/queue.json")
        val s = UploadQueueStore(f)
        s.put(item("s1")); s.put(item("s2", b))
        val restored = UploadQueueStore(f)
        assertEquals(listOf("s1", "s2"), restored.snapshot().map { it.id })
        assertEquals("demo2", restored.get("s2")!!.inspector.username)
        assertEquals("D2", restored.get("s2")!!.inspector.initials)
    }

    @Test fun sendingItemIsWaitingAfterRestart() {
        val f = File(tmp.root, "queue.json")
        UploadQueueStore(f).apply { put(item("s1")); modify("s1") { it.copy(state = QueueState.SENDING) } }
        assertEquals(QueueState.WAITING, UploadQueueStore(f).get("s1")!!.state)
    }

    @Test fun unreadableFileIsKeptAsBadAndQueueStartsEmpty() {
        val f = File(tmp.root, "queue.json").apply { writeText("not json") }
        assertTrue(UploadQueueStore(f).snapshot().isEmpty())
        assertTrue(File(tmp.root, "queue.json.bad").exists())
    }

    @Test fun supersedeDropsOnlyUnsavedItemsOfTheSameInspectorAndRma() {
        val s = UploadQueueStore(File(tmp.root, "q.json"))
        s.put(item("old")); s.put(item("saved", saved = true)); s.put(item("other-rma", rma = "912346")); s.put(item("other-user", b))
        s.supersede("demo", "912345")
        assertEquals(setOf("saved", "other-rma", "other-user"), s.snapshot().map { it.id }.toSet())
    }

    // ------------------------------------------------------------ rules

    @Test fun backoffScheduleGrowsAndCaps() {
        assertEquals(15_000, QueueRules.backoffMillis(1))
        assertEquals(30_000, QueueRules.backoffMillis(2))
        assertEquals(60_000, QueueRules.backoffMillis(3))
        assertEquals(600_000, QueueRules.backoffMillis(6))
        assertEquals(900_000, QueueRules.backoffMillis(7))
        assertEquals(900_000, QueueRules.backoffMillis(50))
        assertEquals(180_000, QueueRules.backoffMillis(1, locked = true))
    }

    @Test fun decisions() {
        assertEquals(QueueDecision.RETRY, QueueRules.decide(fail(ApiResult.Kind.UNREACHABLE)))
        assertEquals(QueueDecision.RETRY, QueueRules.decide(fail(status = 503, code = "LOCK_CHECK_FAILED")))
        assertEquals(QueueDecision.RETRY, QueueRules.decide(fail(status = 423, code = "RMA_LOCKED")))
        assertEquals(QueueDecision.HOLD, QueueRules.decide(fail(status = 401, code = "UNAUTHENTICATED")))
        assertEquals(QueueDecision.BLOCK, QueueRules.decide(fail(status = 409, code = "RMA_CHANGED")))
        assertEquals(QueueDecision.BLOCK, QueueRules.decide(fail(status = 409, code = "REPORT_PICS_CHANGED")))
        assertEquals(QueueDecision.BLOCK, QueueRules.decide(fail(status = 422, code = "VALIDATION_FAILED")))
    }

    @Test fun heldVersusSendableByInspector() {
        val items = listOf(item("a1", a), item("b1", b), item("a2", a).copy(nextTryAt = 5_000), item("a3", a).copy(state = QueueState.BLOCKED))
        assertEquals(listOf("a1"), QueueRules.sendable(items, "demo", now = 1_000).map { it.id })
        assertEquals(listOf("a1", "a2"), QueueRules.sendable(items, "demo", now = 1_000, force = true).map { it.id })
        assertEquals(listOf("b1"), QueueRules.sendable(items, "demo2", now = 1_000).map { it.id })
        assertTrue(QueueRules.sendable(items, null, now = 1_000).isEmpty())
        assertEquals(listOf("b1"), QueueRules.held(items, "demo").map { it.id })
        assertEquals(listOf("a1", "a2"), QueueRules.waitingFor(items, "demo").map { it.id })
    }

    // ------------------------------------------------------------ engine

    private class Fake : QueueGateway {
        var submitResult: () -> ApiResult<InspectionSaved> = { ApiResult.Ok(InspectionSaved(result = "saved", parts = listOf(SavedPart("L1.new")))) }
        var photoResult: () -> ApiResult<PhotoUploaded> = { ApiResult.Ok(PhotoUploaded("x", filename = "01-1 Bearing cap.jpg")) }
        val submits = mutableListOf<Pair<String, String>>() // submission_id to token
        val uploads = mutableListOf<Pair<String, String>>() // photo_id to token
        override suspend fun submitInspection(rmaId: String, payload: JsonObject, token: String): ApiResult<InspectionSaved> {
            submits += payload["submission_id"].toString().trim('"') to token; return submitResult()
        }
        override suspend fun uploadPhoto(rmaId: String, meta: PhotoMetaWrite, file: File, token: String): ApiResult<PhotoUploaded> {
            uploads += meta.photoId to token; return photoResult()
        }
        override suspend fun rebuildReport(rmaId: String, submissionId: String, token: String) = ApiResult.Ok(ReportRebuilt("ok"))
    }

    private inner class Rig(var current: Session?) {
        val store = UploadQueueStore(File(tmp.root, "queue.json"))
        val photos = PhotoStore(File(tmp.root, "photos"))
        val drafts = DraftStore(File(tmp.root, "drafts"))
        val gateway = Fake()
        var clock = 1_000_000L
        var rejected: String? = null
        val engine = UploadQueueEngine(store, gateway, photos, drafts, ReportDraftStore(File(tmp.root, "rd.json")),
            currentSession = { current }, onUnauthorized = { rejected = it }, now = { clock })

        fun addPhoto(user: String, id: String) {
            photos.newFile(user, "912345", id).writeBytes(byteArrayOf(1, 2, 3))
            photos.add(user, "912345", LocalPhoto(id, LocalPhoto.KIND_PART, rowLocalId = "new-1", capturedAt = "2026-11-17T10:49:02-05:00", file = "$id.jpg", sha256 = "abc"))
        }
    }

    @Test fun offlineThenOnlineSendsOnceAndClearsPhotos() = runBlocking {
        val r = Rig(session(a))
        r.addPhoto("demo", "p1")
        r.store.put(item("s1", photoIds = listOf("p1")))
        r.gateway.submitResult = { fail(ApiResult.Kind.UNREACHABLE) }
        assertEquals(0, r.engine.drain())
        val waiting = r.store.get("s1")!!
        assertEquals(QueueState.WAITING, waiting.state)
        assertEquals(1, waiting.attempts)
        assertEquals(r.clock + 15_000, waiting.nextTryAt)
        // Not due yet: no second call.
        r.engine.drain()
        assertEquals(1, r.gateway.submits.size)
        // Back online, after the back-off.
        r.clock += 20_000
        r.gateway.submitResult = { ApiResult.Ok(InspectionSaved(result = "saved", parts = listOf(SavedPart("L1.new")))) }
        assertEquals(1, r.engine.drain())
        assertNull(r.store.get("s1"))
        assertEquals(listOf("p1"), r.gateway.uploads.map { it.first })
        assertTrue(r.photos.list("demo", "912345").single().isSent)
        assertTrue(r.photos.pending("demo", "912345").isEmpty())
        // Nothing left: another run writes nothing.
        r.engine.drain()
        assertEquals(2, r.gateway.submits.size)
    }

    @Test fun conflict409BlocksAndIsNeverRetried() = runBlocking {
        val r = Rig(session(a))
        r.store.put(item("s1"))
        r.gateway.submitResult = { fail(status = 409, code = "RMA_CHANGED") }
        r.engine.drain()
        val blocked = r.store.get("s1")!!
        assertEquals(QueueState.BLOCKED, blocked.state)
        assertEquals("RMA_CHANGED", blocked.reasonCode)
        r.clock += 10 * 3_600_000L
        r.engine.drain(); r.engine.drain(force = true)
        assertEquals(1, r.gateway.submits.size)
    }

    @Test fun heldItemIsNeverSentWithAnotherUsersToken() = runBlocking {
        val r = Rig(session(b))
        r.store.put(item("s1", a))
        r.store.put(item("s2", b))
        r.engine.drain(force = true)
        assertEquals(listOf("s2" to "tok-demo2"), r.gateway.submits)
        assertNotNull(r.store.get("s1"))
        // Signed out: nothing is sent at all.
        r.current = null
        r.engine.drain(force = true)
        assertEquals(1, r.gateway.submits.size)
        // The same inspector signs in again: now it goes, with their token.
        r.current = session(a)
        r.engine.drain()
        assertEquals("s1" to "tok-demo", r.gateway.submits.last())
        assertNull(r.store.get("s1"))
    }

    @Test fun unauthorizedKeepsTheItemAndReportsTheToken() = runBlocking {
        val r = Rig(session(a))
        r.store.put(item("s1"))
        r.gateway.submitResult = { fail(status = 401, code = "UNAUTHENTICATED") }
        r.engine.drain()
        assertEquals("tok-demo", r.rejected)
        val kept = r.store.get("s1")!!
        assertEquals(QueueState.WAITING, kept.state)
        assertEquals(0, kept.attempts)
    }

    @Test fun retryAfterSavedDoesNotSubmitFindingsAgain() = runBlocking {
        val r = Rig(session(a))
        r.addPhoto("demo", "p1")
        r.store.put(item("s1", photoIds = listOf("p1")))
        r.gateway.photoResult = { fail(ApiResult.Kind.UNREACHABLE) }
        r.engine.drain()
        assertTrue(r.store.get("s1")!!.inspectionSaved)
        assertEquals(1, r.store.get("s1")!!.photoIds.size)
        r.clock += 60_000
        r.gateway.photoResult = { ApiResult.Ok(PhotoUploaded("p1", filename = "01-1 x.jpg")) }
        assertEquals(1, r.engine.drain())
        assertEquals(1, r.gateway.submits.size)
        assertNull(r.store.get("s1"))
    }

    @Test fun draftIsClearedOnlyWhenItStillMatchesTheSubmit() = runBlocking {
        val r = Rig(session(a))
        val rma = RmaDetail(rmaId = "912345", displayNumber = "912345", signature = "sig-1", isOpen = true, inspectable = true,
            productType = "Recip", productTypeKey = "recip", capabilities = Capabilities(true, true, true, true, true))
        val it1 = item("s1")
        r.drafts.write(Draft.start(rma, a, it1.draftRows))
        r.store.put(it1)
        r.engine.drain()
        assertNull(r.drafts.read("demo", "912345"))

        r.drafts.write(Draft.start(rma, a, it1.draftRows + FindingRow("new-2")))
        r.store.put(item("s2"))
        r.engine.drain()
        assertNotNull(r.drafts.read("demo", "912345"))
        assertFalse(r.store.snapshot().any { it.id == "s2" })
    }
}
