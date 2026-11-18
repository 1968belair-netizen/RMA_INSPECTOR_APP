package com.bitzer.rmainspector.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Issue #3: search, work list, detail. Fake data only. */
class LookupApiTest {
    private lateinit var server: MockWebServer
    private val api = ApiClient()
    private val base get() = server.url("/api/v1").toString().trimEnd('/')

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    @Test
    fun searchSendsWhateverWasTypedEncoded() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"query":"QN 4000/12","matched_on":"none","results":[]}"""))
        val r = api.search(base, "  QN 4000/12 ") as ApiResult.Ok
        assertEquals("none", r.value.matchedOn)
        val req = server.takeRequest()
        assertEquals("/api/v1/rmas/search", req.requestUrl!!.encodedPath)
        assertEquals("QN 4000/12", req.requestUrl!!.queryParameter("q"))
    }

    @Test
    fun opaqueRmaIdIsPercentEncodedInThePath() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"rma_id":"A/B 12","display_number":"A-B 12","signature":"sig-1"}"""))
        val r = api.rmaDetail(base, "A/B 12") as ApiResult.Ok
        assertEquals("A-B 12", r.value.displayNumber)
        assertEquals("/api/v1/rmas/A%2FB%2012", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun workListSendsStatusCodesWithGroups() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"results":[{"rma_id":"912345","display_number":"912345","model":"4FE-28Y",
               "return_code":{"group":null,"code":"Warranty","description":"Warranty"},
               "inspection_status":{"group":null,"code":"Received","description":"Received"},
               "status_since":"2026-10-26","status_since_estimated":true,
               "flags":[{"code":"CORE_TEARDOWN","label":"In core teardown log"}],
               "is_open":true,"inspectable":true,"locked":false}],"generated_at":"2026-11-17T10:00:00-05:00"}"""))
        val r = api.workList(base, listOf(CodeKey(null, "Received"), CodeKey("ZRMA", "E0003"))) as ApiResult.Ok
        assertEquals("912345", r.value.results.single().rmaId)
        val item = r.value.results.single()
        assertEquals("Warranty", item.returnCode?.code)
        assertEquals("2026-10-26", item.statusSince)
        assertTrue(item.statusSinceEstimated)
        assertEquals("CORE_TEARDOWN", item.flags.single().code)
        val url = server.takeRequest().requestUrl!!
        assertEquals("Received,ZRMA:E0003", url.queryParameter("inspection_status"))
        assertEquals("50", url.queryParameter("limit"))
    }

    @Test
    fun emptyStatusListMeansServerDefault() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        api.workList(base, emptyList())
        assertNull(server.takeRequest().requestUrl!!.queryParameter("inspection_status"))
    }

    @Test
    fun notFoundIsReportedWithCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"error":{"code":"RMA_NOT_FOUND","message":"RMA 999 was not found.","retryable":false,"details":{}}}"""))
        val r = api.rmaDetail(base, "999") as ApiResult.Failure
        assertEquals("RMA_NOT_FOUND", r.code)
        assertTrue(r.message.contains("not found"))
    }

    @Test
    fun badBaseUrlFailsCleanly() = runBlocking {
        val r = api.search("not a url", "912345") as ApiResult.Failure
        assertEquals(ApiResult.Kind.UNREACHABLE, r.kind)
    }

    @Test
    fun statusQueryFormat() {
        assertEquals("Received,Report Complete", StatusQuery.format(listOf(CodeKey(null, "Received"), CodeKey(null, "Report Complete"))))
        assertEquals("G:C", StatusQuery.format(listOf(CodeKey("G", "C"))))
    }
}
