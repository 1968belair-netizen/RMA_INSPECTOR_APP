package com.bitzer.rmainspector.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Submit inspection (spec §6, api §7–8): status rules, payload, review, photo upload sequencing, errors. FAKE data. */
class SubmitTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun c(code: String, desc: String = code) = Code(null, code, desc)

    private val statuses = listOf("Open", "Received", "Inspected", "Rework in Progress", "Report Complete", "Sent to RSM",
        "Issued to Customer", "Non-Warranty Processed", "Closed").map { c(it) }
    private val roles = StatusRoles()

    private fun plan(status: String?) = StatusPlan.decide(status?.let { c(it) }, roles, statuses)

    // ------------------------------------------------------------------------------------ status rules (spec §6.1)

    @Test fun beforeInspectedSetsInspected() {
        for (s in listOf("Open", "Received", "Rework in Progress")) {
            val p = plan(s)
            assertEquals(s, SubmitKind.SET_INSPECTED, p.kind)
            assertEquals(CodeKey(null, "Inspected"), p.change!!.to)
            assertEquals(CodeKey(null, s), p.change!!.from)
            assertEquals("Inspected", p.resultingStatus)
            assertFalse(p.rebuildsReport)
        }
    }

    @Test fun inspectedKeepsTheStatus() {
        val p = plan("Inspected")
        assertEquals(SubmitKind.KEEP, p.kind)
        assertNull(p.change)
        assertEquals("Inspected", p.resultingStatus)
    }

    @Test fun reportCompleteAsksAndRebuilds() {
        for (s in listOf("Report Complete", "Non-Warranty Processed")) {
            val p = plan(s)
            assertEquals(s, SubmitKind.REPORT_COMPLETE, p.kind)
            assertNull(p.change)
            assertTrue(p.rebuildsReport)
            assertEquals(s, p.resultingStatus)
        }
    }

    @Test fun sentStatusesWarnAboutResending() {
        for (s in listOf("Sent to RSM", "Issued to Customer")) {
            val p = plan(s)
            assertEquals(s, SubmitKind.REPORT_SENT, p.kind)
            assertNull(p.change)
            assertTrue(p.rebuildsReport)
        }
    }

    @Test fun statusRolesComeFromTheServer() {
        val custom = StatusRoles(inspected = "Done", reportComplete = "Built", reportSent = listOf("Mailed"))
        val list = listOf("Received", "Done", "Built", "Mailed").map { c(it) }
        assertEquals(SubmitKind.REPORT_SENT, StatusPlan.decide(c("Mailed"), custom, list).kind)
        assertEquals(SubmitKind.KEEP, StatusPlan.decide(c("Done"), custom, list).kind)
        assertEquals(CodeKey(null, "Done"), StatusPlan.decide(c("Received"), custom, list).change!!.to)
    }

    @Test fun codeListsParseStatusRoles() {
        val l = ApiClient.defaultJson.decodeFromString(CodeLists.serializer(),
            """{"status_roles":{"inspected":"Inspected","report_complete":"Report Complete","report_sent":["Sent to RSM"]}}""")
        assertEquals(listOf("Sent to RSM"), l.statusRoles.reportSent)
        assertEquals("Report Complete", l.statusRoles.reportComplete)
        // Older cached code lists have no status_roles: the defaults apply.
        assertEquals(listOf("Sent to RSM", "Issued to Customer"), CodeLists().statusRoles.reportSent)
    }

    // ------------------------------------------------------------------------------------ fixtures

    private val serverParts = listOf(
        PartRow("L1.a", c("07a", "Terminal box"), c("50", "Normal")),
        PartRow("L2.a", c("48a", "Winding resistance"), c("50", "Normal"), note = "ok"),
    )
    private val rma = RmaDetail(
        rmaId = "912345", displayNumber = "912345", signature = "sig-1", isOpen = true, inspectable = true,
        productType = "Recip", productTypeKey = "recip", capabilities = Capabilities(true, true, true, true, true),
        header = RmaHeader(inspectionStatus = c("Received")), parts = serverParts,
        summary = Summary(exists = true, defectCode = c("10"), blocks = listOf(SummaryBlock("1", "Old text")), inspectedBy = "JD", summaryBy = "JD"),
    )
    private val codes = CodeLists(inspectionStatuses = statuses, summaryBlocks = listOf(SummaryBlockDef("1", "Report summary", 4000)))
    private fun input(rows: List<FindingRow>, photos: List<LocalPhoto> = emptyList(), report: ReportDraft? = null, r: RmaDetail = rma) =
        SubmitInput(r, r, r.signature, rows, photos, codes, report, "demo", "device-1")

    private val unchangedRows = serverParts.toFindingRows()
    private val newRow = FindingRow("new-1", null, c("63", "Piston assy"), c("34", "Scored"), note = "Cyl 2\nlight")

    private fun payload(i: SubmitInput, id: String = "sub-1"): JsonObject =
        SubmitPayload.build(i, SubmitReview.build(i).plan, id, "2026-11-18T10:00:00-05:00")

    // ------------------------------------------------------------------------------------ payload (api §7)

    @Test fun newRowsGoWithNullRowIdAndExistingKeepTheirs() {
        val p = payload(input(unchangedRows + newRow))
        val parts = p["parts"] as JsonArray
        assertEquals(3, parts.size)
        assertEquals("L1.a", parts[0].jsonObject["row_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, parts[2].jsonObject["row_id"])
        assertEquals("63", parts[2].jsonObject["part_code"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, parts[2].jsonObject["part_code"]!!.jsonObject["group"])
        assertEquals(JsonNull, parts[2].jsonObject["cause_code"])
        assertEquals("Cyl 2\nlight", parts[2].jsonObject["note"]!!.jsonPrimitive.content)
    }

    @Test fun envelopeCarriesIdsAndStatusChange() {
        val p = payload(input(unchangedRows + newRow))
        assertEquals("sub-1", p["submission_id"]!!.jsonPrimitive.content)
        assertEquals("sig-1", p["base_signature"]!!.jsonPrimitive.content)
        assertEquals("recip", p["product_type_key"]!!.jsonPrimitive.content)
        assertEquals("device-1", p["device_id"]!!.jsonPrimitive.content)
        val sc = p["status_change"]!!.jsonObject
        assertEquals("Received", sc["from"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("Inspected", sc["to"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun unchangedPartsAndNoSummaryAreNull() {
        val p = payload(input(unchangedRows))
        assertEquals(JsonNull, p["parts"])
        assertEquals(JsonNull, p["summary"])
    }

    @Test fun keepStatusSendsNoStatusChange() {
        val inspected = rma.copy(header = RmaHeader(inspectionStatus = c("Inspected")))
        val p = payload(input(unchangedRows + newRow, r = inspected))
        assertEquals(JsonNull, p["status_change"])
    }

    @Test fun deletedRowsAreLeftOut() {
        val parts = payload(input(listOf(unchangedRows[1])))["parts"] as JsonArray
        assertEquals(1, parts.size)
        assertEquals("L2.a", parts[0].jsonObject["row_id"]!!.jsonPrimitive.content)
    }

    @Test fun summaryHoldsOnlyChangedParts() {
        val draft = ReportDraft(rmaId = "912345", defectCode = c("10"), summaries = mapOf("1" to "New text"),
            inspectedBy = "JD", summaryBy = "JD", fieldsDirty = true)
        val s = payload(input(unchangedRows, report = draft))["summary"]!!.jsonObject
        assertEquals(setOf("blocks"), s.keys) // defect code and by-fields equal the server's: omitted
        val block = (s["blocks"] as JsonArray)[0].jsonObject
        assertEquals("1", block["block_id"]!!.jsonPrimitive.content)
        assertEquals("New text", block["text"]!!.jsonPrimitive.content)
    }

    @Test fun clearingTheDefectCodeSendsExplicitNull() {
        val draft = ReportDraft(rmaId = "912345", defectCode = null, summaries = mapOf("1" to "Old text"), inspectedBy = "JD", summaryBy = "JD", fieldsDirty = true)
        val s = payload(input(unchangedRows, report = draft))["summary"]!!.jsonObject
        assertEquals(setOf("defect_code"), s.keys)
        assertEquals(JsonNull, s["defect_code"])
    }

    @Test fun untouchedReportDraftSendsNoSummary() {
        val draft = ReportDraft(rmaId = "912345", defectCode = c("99"), summaries = mapOf("1" to "x"), fieldsDirty = false)
        assertEquals(JsonNull, payload(input(unchangedRows, report = draft))["summary"])
    }

    @Test fun retryOfTheSameSubmitHasTheSameFingerprint() {
        val i = input(unchangedRows + newRow)
        val a = SubmitPayload.build(i, SubmitReview.build(i).plan, "one", "2026-11-18T10:00:00-05:00")
        val b = SubmitPayload.build(i, SubmitReview.build(i).plan, "two", "2026-11-18T10:05:00-05:00")
        assertEquals(SubmitPayload.fingerprint(a), SubmitPayload.fingerprint(b))
        val other = input(unchangedRows)
        assertNotEquals(SubmitPayload.fingerprint(a), SubmitPayload.fingerprint(payload(other)))
    }

    // ------------------------------------------------------------------------------------ review (spec §6.1)

    @Test fun reviewListsAddedChangedRemovedAndPhotos() {
        val changed = unchangedRows[1].copy(issueCode = c("39", "Winding fault"))
        val photo = LocalPhoto("p1", LocalPhoto.KIND_PART, rowLocalId = "new-1", capturedAt = "x", file = "p1.jpg")
        val orphan = LocalPhoto("p2", LocalPhoto.KIND_PART, rowLocalId = "gone", capturedAt = "x", file = "p2.jpg")
        val rows = listOf(changed, newRow) // 07a removed
        val r = SubmitReview.build(input(rows, listOf(photo, orphan)))
        assertEquals(1, r.added.size)
        assertEquals(1, r.changed.size)
        assertEquals(1, r.removed.size)
        assertEquals(1, r.photosToSend)
        assertEquals(1, r.photosSkipped)
        assertTrue(r.canSubmit)
        assertEquals(SubmitKind.SET_INSPECTED, r.plan.kind)
    }

    @Test fun incompleteRowsBlockTheSubmit() {
        val r = SubmitReview.build(input(unchangedRows + FindingRow("new-2", null, c("64", "Rings"), null)))
        assertEquals(1, r.incomplete.size)
        assertFalse(r.canSubmit)
    }

    @Test fun nothingToSendIsOnlyAllowedWhenItSetsInspected() {
        assertTrue(SubmitReview.build(input(unchangedRows)).canSubmit) // Received → Inspected needs no other change
        val complete = rma.copy(header = RmaHeader(inspectionStatus = c("Report Complete")))
        assertFalse(SubmitReview.build(input(unchangedRows, r = complete)).canSubmit)
        assertTrue(SubmitReview.build(input(unchangedRows + newRow, r = complete)).canSubmit)
    }

    @Test fun reorderingCountsAsAChange() {
        val r = SubmitReview.build(input(unchangedRows.reversed()))
        assertTrue(r.reordered)
        assertTrue(r.partsChanged)
    }

    // ------------------------------------------------------------------------------------ photo upload sequencing

    private fun photo(id: String, row: String? = null, tag: String? = null, sent: Boolean = false) = LocalPhoto(
        id, if (row != null) LocalPhoto.KIND_PART else LocalPhoto.KIND_RMA, rowLocalId = row, tag = tag,
        capturedAt = "2026-11-17T10:49:02-05:00", file = "$id.jpg", sentAt = if (sent) "2026-11-17T11:00:00-05:00" else null)

    @Test fun markSentOnlyAfterTheServerConfirmed() = runBlocking {
        val rows = listOf(FindingRow("r1", "r1", c("07a"), c("50")), FindingRow("r2", "r2", c("48a"), c("50")))
        val photos = listOf(photo("a", "r1"), photo("b", "r2"), photo("c", tag = "Nameplate"), photo("old", "r1", sent = true), photo("lost", "gone"))
        val marked = mutableListOf<String>()
        val order = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val s = PhotoUploadRunner.run(
            photos, rows,
            upload = { meta, p ->
                order += p.photoId
                if (p.photoId == "b") PhotoOutcome.Rejected("422 unknown code") else PhotoOutcome.Sent("name-${meta.seq}.jpg")
            },
            markSent = { p, name -> marked += "${p.photoId}:$name" },
            onProgress = { d, t -> progress += d to t },
        )
        assertEquals(listOf("a", "b", "c"), order) // capture order; sent and orphaned photos are not uploaded
        assertEquals(listOf("a:name-1.jpg", "c:name-0.jpg"), marked) // "b" was refused → stays pending
        assertEquals(2, s.sent)
        assertEquals(listOf("422 unknown code"), s.failed)
        assertEquals(1, s.skipped) // "lost": its row is gone
        assertFalse(s.allSent)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test fun offlineStopsTheUploadAndKeepsTheRest() = runBlocking {
        val rows = listOf(FindingRow("r1", "r1", c("07a"), c("50")))
        val marked = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val s = PhotoUploadRunner.run(
            listOf(photo("a", "r1"), photo("b", "r1"), photo("c", "r1")), rows,
            upload = { _, p -> calls += p.photoId; if (p.photoId == "a") PhotoOutcome.Sent("x.jpg") else PhotoOutcome.Offline("Can't reach the server") },
            markSent = { p, _ -> marked += p.photoId },
        )
        assertEquals(listOf("a", "b"), calls) // stops at the first unreachable answer
        assertEquals(listOf("a"), marked)
        assertTrue(s.stoppedOffline)
        assertFalse(s.allSent)
    }

    @Test fun uploadMetaUsesTheSavedRowIdAndPosition() {
        val saved = unchangedRows.zip(listOf("L1.b", "L2.b")) { r, id -> r.copy(localId = id, rowId = id) }
        val meta = PhotoUpload.meta(photo("a", "L2.b"), saved)!!
        assertEquals(2, meta.seq)
        assertEquals("L2.b", meta.rowId)
    }

    // ------------------------------------------------------------------------------------ errors (spec §6.4)

    private fun fail(kind: ApiResult.Kind = ApiResult.Kind.SERVER_ERROR, code: String? = null, status: Int? = null, msg: String = "Server says no.") =
        ApiResult.Failure(kind, msg, code = code, httpStatus = status)

    @Test fun errorsShowTheServerMessageAndKeepTheWork() {
        val offline = SubmitErrors.map(fail(kind = ApiResult.Kind.UNREACHABLE, msg = "Can't reach"))
        assertTrue(offline.offline)
        assertTrue(offline.message.contains("Nothing was lost"))
        assertTrue(offline.message.contains(SubmitErrors.KEPT))

        val changed = SubmitErrors.map(fail(code = "RMA_CHANGED", status = 409, msg = "Someone changed RMA 912345 after you loaded it. Nothing was saved."))
        assertTrue(changed.message.startsWith("Someone changed RMA 912345"))
        assertTrue(changed.message.contains(SubmitErrors.KEPT))

        val locked = SubmitErrors.map(fail(code = "RMA_LOCKED", status = 423, msg = "Open in Macola by JDOE2."))
        assertEquals("Open in Macola", locked.title)
        assertTrue(locked.message.startsWith("Open in Macola by another user — will retry"))
        assertTrue(locked.message.contains(SubmitErrors.KEPT))
        assertFalse(locked.offline)

        assertEquals("The server rejected the findings", SubmitErrors.map(fail(status = 422, code = "VALIDATION_FAILED")).title)
        assertEquals("RMA is closed", SubmitErrors.map(fail(code = "RMA_NOT_OPEN", status = 409)).title)
    }

    // ------------------------------------------------------------------------------------ API calls

    private lateinit var server: MockWebServer
    private val api = ApiClient()
    private val base get() = server.url("/api/v1").toString().trimEnd('/')

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun submitInspectionPostsAndReadsNewRowIds() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":"saved","submission_id":"sub-1","replayed":false,"rma_id":"912345",
            "new_signature":"sig-2","verified":true,"changes":{},"parts":[{"row_id":"L1.b","part_code":{"group":null,"code":"07a"},
            "issue_code":{"group":null,"code":"50"},"cause_code":null}],"warnings":[]}"""))
        val i = input(unchangedRows + newRow)
        val r = (api.submitInspection(base, "912345", payload(i)) as ApiResult.Ok).value
        assertEquals("sig-2", r.newSignature)
        assertEquals("L1.b", r.parts[0].rowId)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/rmas/912345/inspection", req.requestUrl!!.encodedPath)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"base_signature\":\"sig-1\""))
        assertTrue(body.contains("\"row_id\":null"))
    }

    @Test fun submitInspection409IsAFailureWithTheServerMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"RMA_CHANGED","message":"Someone changed RMA 912345 after you loaded it. Nothing was saved.","retryable":false,"details":{}}}"""))
        val f = api.submitInspection(base, "912345", payload(input(unchangedRows))) as ApiResult.Failure
        assertEquals("RMA_CHANGED", f.code)
        assertTrue(SubmitErrors.map(f).message.startsWith("Someone changed RMA 912345"))
    }

    @Test fun uploadPhotoSendsMetaAndFileAndAcceptsAReplay() = runBlocking {
        val jpg = tmp.newFile("a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"photo_id":"a","replayed":false,"filename":"01-1 Terminal box - 50 Normal.jpg"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"photo_id":"a","replayed":true,"filename":"01-1 Terminal box - 50 Normal.jpg"}"""))
        val meta = PhotoUpload.meta(photo("a", "L1.a"), unchangedRows)!!
        val first = (api.uploadPhoto(base, "912345", meta, jpg) as ApiResult.Ok).value
        assertEquals("01-1 Terminal box - 50 Normal.jpg", first.filename)
        val req = server.takeRequest()
        assertEquals("/api/v1/rmas/912345/photos", req.requestUrl!!.encodedPath)
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("name=\"meta\""))
        assertTrue(body.contains("\"photo_id\":\"a\""))
        assertTrue(body.contains("\"row_id\":\"L1.a\""))
        assertTrue(body.contains("name=\"file\""))
        val second = (api.uploadPhoto(base, "912345", meta, jpg) as ApiResult.Ok).value
        assertTrue(second.replayed)
    }

    @Test fun uploadPhotoUnreachableIsReportedAsOffline() = runBlocking {
        val jpg = tmp.newFile("b.jpg").apply { writeBytes(byteArrayOf(1)) }
        val f = api.uploadPhoto("http://127.0.0.1:1/api/v1", "912345", PhotoUpload.meta(photo("b", "L1.a"), unchangedRows)!!, jpg) as ApiResult.Failure
        assertEquals(ApiResult.Kind.UNREACHABLE, f.kind)
    }

    // ------------------------------------------------------------------------------------ photo store: rows follow new ids

    @Test fun remapRowsMovesPhotosToTheNewRowIds() {
        val store = PhotoStore(tmp.newFolder("photos"))
        store.add("demo", "912345", photo("a", "L1.a"))
        store.add("demo", "912345", photo("b", "new-1"))
        store.add("demo", "912345", photo("c", tag = "Nameplate"))
        store.remapRows("demo", "912345", mapOf("L1.a" to "L1.b", "new-1" to "L3.b"))
        val all = store.list("demo", "912345")
        assertEquals(listOf("L1.b", "L3.b", null), all.map { it.rowLocalId })
        assertEquals(3, store.pending("demo", "912345").size)
    }
}
