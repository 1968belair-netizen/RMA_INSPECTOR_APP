package com.bitzer.rmainspector.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Report photo endpoints (api §8, §9.2): list, PUT, 409 REPORT_PICS_CHANGED. FAKE data. */
class ReportApiTest {
    private lateinit var server: MockWebServer
    private val api = ApiClient()
    private val base get() = server.url("/api/v1").toString().trimEnd('/')

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    @Test
    fun photoListingReadsPartLinksAndReportPics() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"folder_found":true,"folder_display":"912xxx","all_pics":[
            {"filename":"03-1 Piston assy - 34 Scored.jpg","bytes":612344,"modified":"x","seq":3,
             "part_code":{"group":null,"code":"63"},"row_id":"L3.8a"},
            {"filename":"00-1 As received.jpg","bytes":1,"seq":0,"part_code":null,"row_id":null}],
            "report_pics":{"folder_found":false,"signature":"rp-empty","files":[]}}"""))
        val r = (api.photoListing(base, "912345") as ApiResult.Ok).value
        assertEquals("63", r.allPics[0].partCode?.code)
        assertEquals("L3.8a", r.allPics[0].rowId)
        assertEquals("rp-empty", r.reportPics.signature)
        assertEquals("/api/v1/rmas/912345/photos", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun putReportPicsSendsPutWithBaseSignature() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":"saved","replayed":false,"new_signature":"rp-7c21e0","folder_created":true,
            "changes":{"copied":1,"renamed":0,"removed":0,"kept":0},"report_pics":["00-1 As received.jpg"]}"""))
        val d = ReportDraft(picksBaseSignature = "rp-empty").add("00-1 As received.jpg")
        val r = (api.putReportPics(base, "912345", d.toRequest("sub-1")) as ApiResult.Ok).value
        assertEquals("rp-7c21e0", r.newSignature)
        assertEquals(1, r.changes.copied)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/rmas/912345/report-pics", req.requestUrl!!.encodedPath)
        assertTrue(req.body.readUtf8().contains("\"base_signature\":\"rp-empty\""))
    }

    @Test
    fun reportPicsChangedKeepsTheServersCurrentList() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"REPORT_PICS_CHANGED",
            "message":"Report photos were changed elsewhere after you loaded them. Please reload.",
            "details":{"current_signature":"rp-new","current":["06 Desk photo.jpg"]}}}"""))
        val f = api.putReportPics(base, "912345", ReportDraft(picksBaseSignature = "rp-old").toRequest("sub-2")) as ApiResult.Failure
        assertEquals("REPORT_PICS_CHANGED", f.code)
        assertEquals(409, f.httpStatus)
        assertEquals("rp-new", (f.details?.get("current_signature") as JsonPrimitive).content)
        assertEquals(1, (f.details?.get("current") as JsonArray).size)
    }

    @Test
    fun photoBytesAsksForThumbOrFull() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        val r = (api.photoBytes(base, "912345", "all", "00-1 As received.jpg", thumb = true) as ApiResult.Ok).value
        assertEquals(3, r.size)
        val url = server.takeRequest().requestUrl!!
        assertEquals("thumb", url.queryParameter("size"))
        assertEquals("/api/v1/rmas/912345/photos/all/00-1%20As%20received.jpg", url.encodedPath)
    }
}
