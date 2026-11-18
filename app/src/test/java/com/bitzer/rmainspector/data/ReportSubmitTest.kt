package com.bitzer.rmainspector.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Submit report (C2): spell-check flow, build request, status warnings, error mapping, PDF page count. FAKE data. */
class ReportSubmitTest {
    private lateinit var server: MockWebServer
    private val api = ApiClient()
    private val base get() = server.url("/api/v1").toString().trimEnd('/')

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    // --- spell-check flow
    private val issue = SpellIssue("scorring", 32, 40, listOf("scoring"))
    private val text = "The primary failure mode is the scorring of the piston."

    @Test fun fixReplacesAtOffsetsAndKeepsCase() {
        val at = text.indexOf("scorring")
        val i = issue.copy(start = at, end = at + 8)
        assertEquals("The primary failure mode is the scoring of the piston.", SpellFlow.applyFix(text, i, "scoring"))
        assertEquals("Scoring.", SpellFlow.applyFix("Scorring.", SpellIssue("Scorring", 0, 8), "scoring"))
    }

    @Test fun fixFallsBackToFirstWordMatchWhenOffsetsAreStale() {
        assertEquals("The primary failure mode is the scoring of the piston.", SpellFlow.applyFix(text, issue, "scoring"))
        assertEquals("unchanged", SpellFlow.applyFix("unchanged", issue, "scoring"))
    }

    @Test fun ignoreDropsEveryOccurrenceOfTheWord() {
        val items = listOf(SpellItem("1", "Report summary", issue), SpellItem("1", "Report summary", issue.copy(word = "Scorring")),
            SpellItem("1", "Report summary", SpellIssue("bearring")))
        assertEquals(listOf("bearring"), SpellFlow.remaining(items, setOf("scorring")).map { it.issue.word })
        assertTrue(SpellFlow.remaining(items, setOf("scorring", "bearring")).isEmpty())
    }

    @Test fun spellcheckAndDictionaryCallsUseTheDocumentedBodies() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"issues":[{"word":"scorring","start":32,"end":40,"suggestions":["scoring"]}]}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"word":"slinger","added":true}"""))
        val r = (api.spellcheck(base, text) as ApiResult.Ok).value
        assertEquals("scoring", r.issues.single().suggestions.single())
        assertTrue((api.addToDictionary(base, "slinger") as ApiResult.Ok).value.added)
        val a = server.takeRequest(); val b = server.takeRequest()
        assertEquals("/api/v1/spellcheck", a.requestUrl!!.encodedPath)
        assertTrue(a.body.readUtf8().contains("\"text\""))
        assertEquals("/api/v1/spellcheck/dictionary", b.requestUrl!!.encodedPath)
        assertTrue(b.body.readUtf8().contains("\"word\":\"slinger\""))
    }

    // --- build request
    @Test fun buildSendsSubmissionIdAndBothFormatsAndReadsTheResult() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":"built","replayed":false,"generated_at":"x",
            "files":[{"format":"pdf","filename":"912345 Report.pdf","bytes":1543000,"pages":6},{"format":"xlsx","filename":"912345 Report.xlsx","bytes":88000}],
            "photo_count":3,"previous_backed_up":false,
            "status_changed":{"from":{"group":null,"code":"Inspected"},"to":{"group":null,"code":"Report Complete"},"audited":true},
            "warnings":[{"code":"NO_CREDIT_STATUS","message":"No credit status yet."}]}"""))
        val r = (api.buildReport(base, "912345", BuildRequest("sub-9")) as ApiResult.Ok).value
        assertEquals(6, r.pdf?.pages)
        assertEquals("Report Complete", r.statusChanged?.to?.code)
        assertEquals("NO_CREDIT_STATUS", r.warnings.single().code)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/rmas/912345/report", req.requestUrl!!.encodedPath)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"submission_id\":\"sub-9\""))
        assertTrue(body.contains("\"formats\":[\"pdf\",\"xlsx\"]"))
    }

    @Test fun summaryOnlySaveLeavesStatusAndPartsNull() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":"saved","replayed":false,"new_signature":"sig-2"}"""))
        val d = ReportDraft(rmaId = "912345", defectCode = Code(null, "10"), summaries = mapOf("1" to "Text"), inspectedBy = "DI", summaryBy = "DI")
        val req = SummaryOnlyRequest("s1", "sig-1", "recip", null, d.toSummaryWrite(), null)
        assertEquals("sig-2", (api.saveSummary(base, "912345", req) as ApiResult.Ok).value.newSignature)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"status_change\":null"))
        assertTrue(body.contains("\"parts\":null"))
        assertTrue(body.contains("\"base_signature\":\"sig-1\""))
        assertTrue(body.contains("\"defect_code\":{\"group\":null,\"code\":\"10\"}"))
    }

    @Test fun summaryChangedComparesAgainstTheServer() {
        val server = Summary(true, Code(null, "07"), listOf(SummaryBlock("1", "Example summary text.")), "JD", "JD")
        val same = ReportDraft(defectCode = Code(null, "07", "x"), summaries = mapOf("1" to "Example summary text."), inspectedBy = "JD", summaryBy = "JD")
        assertFalse(ReportSubmitRules.summaryChanged(same, server))
        assertTrue(ReportSubmitRules.summaryChanged(same.withSummary("1", "Edited"), server))
        assertTrue(ReportSubmitRules.summaryChanged(same.withDefect(null), server))
        assertTrue(ReportSubmitRules.summaryChanged(same.withInspectedBy("DI"), server))
        assertEquals("Edited", ReportSubmitRules.summaryAfterSave(same.withSummary("1", "Edited"), server).blocks.single().text)
    }

    // --- status warnings
    @Test fun statusWarningRules() {
        assertEquals(StatusWarning.NONE, ReportSubmitRules.warningFor("Inspected"))
        assertEquals(StatusWarning.NONE, ReportSubmitRules.warningFor(null))
        assertEquals(StatusWarning.ALREADY_COMPLETE, ReportSubmitRules.warningFor("Report Complete"))
        assertEquals(StatusWarning.ALREADY_SENT, ReportSubmitRules.warningFor("Sent to RSM"))
        assertEquals(StatusWarning.ALREADY_SENT, ReportSubmitRules.warningFor("Issued to Customer"))
        assertEquals("This report was already sent. Updating will regenerate it; you may need to resend it.", ReportSubmitRules.SENT_WARNING)
    }

    // --- error mapping
    private fun failure(code: String?, status: Int, message: String = "Server says no.", kind: ApiResult.Kind = ApiResult.Kind.SERVER_ERROR,
                        details: kotlinx.serialization.json.JsonObject? = null, retryable: Boolean = false) =
        ApiResult.Failure(kind, message, code = code, httpStatus = status, retryable = retryable, details = details)

    @Test fun fileInUseNamesTheFileAndCanBeRetried() {
        val e = submitErrorFor(failure("REPORT_FILE_IN_USE", 409, details = buildJsonObject { put("filename", "912345 Report.pdf") }, retryable = true))
        assertEquals(SubmitErrorKind.FILE_IN_USE, e.kind)
        assertTrue(e.message.contains("912345 Report.pdf"))
        assertTrue(e.retryable)
    }

    @Test fun picsChangedAndRmaChangedAreNeverRetriedAutomatically() {
        assertFalse(submitErrorFor(failure("REPORT_PICS_CHANGED", 409)).retryable)
        assertEquals(SubmitErrorKind.PICS_CHANGED, submitErrorFor(failure("REPORT_PICS_CHANGED", 409)).kind)
        assertEquals(SubmitErrorKind.CHANGED_ELSEWHERE, submitErrorFor(failure("RMA_CHANGED", 409)).kind)
    }

    @Test fun validationListsTheServerMessages() {
        val details = buildJsonObject { put("fields", buildJsonArray {
            add(buildJsonObject { put("path", "summary.blocks.1"); put("message", "Report summary is 4,212 characters; the limit is 4,000.") }) }) }
        val e = submitErrorFor(failure("VALIDATION_FAILED", 422, details = details))
        assertEquals(SubmitErrorKind.VALIDATION, e.kind)
        assertTrue(e.message.contains("4,212 characters"))
    }

    @Test fun unreachableAndLockedKeepTheWorkAndOfferRetry() {
        val u = submitErrorFor(failure(null, 0, "Can't reach the server.", ApiResult.Kind.UNREACHABLE, retryable = true))
        assertEquals(SubmitErrorKind.UNREACHABLE, u.kind)
        assertTrue(u.retryable && u.message.contains("Nothing was lost"))
        val l = submitErrorFor(failure("RMA_LOCKED", 423, "RMA 912345 is open in Macola by JDOE2.", retryable = true))
        assertEquals(SubmitErrorKind.LOCKED, l.kind)
        assertTrue(l.retryable)
    }

    // --- PDF page count (tiny fixture)
    private val tinyPdf = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Count 2/Kids[3 0 R 4 0 R]>>endobj\n" +
        "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n4 0 obj<</Type /Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n%%EOF").toByteArray()

    @Test fun pdfBytesAndPageCounts() {
        assertTrue(PdfBytes.looksLikePdf(tinyPdf))
        assertFalse(PdfBytes.looksLikePdf("<html>not a pdf</html>".toByteArray()))
        assertEquals(2, PdfBytes.countPages(tinyPdf))
        assertEquals(6, PdfBytes.resolvePageCount(6, 5, tinyPdf))
        assertEquals(5, PdfBytes.resolvePageCount(null, 5, tinyPdf))
        assertEquals(2, PdfBytes.resolvePageCount(0, null, tinyPdf))
        assertEquals(1, PdfBytes.resolvePageCount(null, null, "%PDF-1.4 empty".toByteArray()))
        assertNull(null as Int?)
    }
}
