package com.bitzer.rmainspector.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Cache refresh, 304, offline use and exact round-trip of code objects. FAKE data only. */
class CodeListRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val url = "http://rma-server.example.invalid/api/v1"

    private val rawV1 = """{"version":"v1","etag":"\"cl-1\"","source":"mock",
      "parts":[{"group":null,"code":"07","description":"Terminal plate"},{"group":"COMP-MECH","code":"0063","description":"Piston assy / skirt"},
               {"group":null,"code":"99 – JSIOC","description":"Example spaced code"}],
      "issues":[{"group":null,"code":"50","description":"Normal","pin_first":true},{"group":null,"code":"13","description":"Heavy wear"}],
      "normal_issue_codes":[{"group":null,"code":"50"}],"hidden":{"parts":[],"issues":[],"causes":[]}}"""
    private val rawV2 = rawV1.replace("\"v1\"", "\"v2\"").replace("cl-1", "cl-2")

    /** Scripted fake server: records the ETag it was sent and answers from [answers] in order. */
    private class FakeSource(vararg answers: ApiResult<CodeListFetch>) : CodeListSource {
        val queue = ArrayDeque(answers.toList())
        val sentEtags = mutableListOf<String?>()
        override suspend fun fetch(baseUrl: String, etag: String?): ApiResult<CodeListFetch> {
            sentEtags += etag
            return queue.removeFirst()
        }
    }

    private fun fresh(raw: String, etag: String) =
        ApiResult.Ok(CodeListFetch.Fresh(raw, etag, ApiClient.defaultJson.decodeFromString(CodeLists.serializer(), raw)))

    private val offline = ApiResult.Failure(ApiResult.Kind.UNREACHABLE, "Can't reach the server.", retryable = true)

    private var now = 1_000_000L
    private fun cacheFile() = File(tmp.root, "codelists/codelists.json")
    private fun repo(source: CodeListSource) = CodeListRepository(source, FileCodeListCache(cacheFile()), clock = { now })

    @Test
    fun firstDownloadIsCachedThenUsableOffline() = runBlocking {
        val r1 = repo(FakeSource(fresh(rawV1, "\"cl-1\"")))
        assertEquals(RefreshOutcome.UPDATED, r1.refresh(url, force = true))
        assertEquals("v1", r1.state.value.lists!!.version)

        // New app start, no network: lists come from the device.
        val src = FakeSource(offline)
        val r2 = repo(src)
        r2.loadCached(url)
        assertEquals("v1", r2.state.value.lists!!.version)
        assertEquals(RefreshOutcome.FAILED, r2.refresh(url, force = true))
        assertEquals("v1", r2.state.value.lists!!.version) // still usable
        assertEquals("Can't reach the server.", r2.state.value.error)
        assertEquals(listOf<String?>("\"cl-1\""), src.sentEtags)
    }

    @Test
    fun notModifiedKeepsListsAndBumpsFetchTime() = runBlocking {
        val src = FakeSource(fresh(rawV1, "\"cl-1\""), ApiResult.Ok(CodeListFetch.NotModified))
        val r = repo(src)
        r.refresh(url, force = true)
        now += 5 * 60 * 60 * 1000L
        assertEquals(RefreshOutcome.NOT_MODIFIED, r.refresh(url, force = true))
        assertEquals(listOf(null, "\"cl-1\""), src.sentEtags)
        assertEquals("v1", r.state.value.lists!!.version)
        assertEquals(now, r.state.value.fetchedAtMillis)
        assertEquals(now, FileCodeListCache(cacheFile()).read()!!.fetchedAtMillis)
    }

    @Test
    fun refreshIfStaleOnlyCallsServerAfterMaxAge() = runBlocking {
        val src = FakeSource(fresh(rawV1, "\"cl-1\""), fresh(rawV2, "\"cl-2\""))
        val r = repo(src)
        r.refresh(url, force = true)
        now += r.maxAgeMillis - 1
        assertEquals(RefreshOutcome.SKIPPED_FRESH, r.refreshIfStale(url))
        assertEquals(1, src.sentEtags.size)
        now += 2
        assertEquals(RefreshOutcome.UPDATED, r.refreshIfStale(url))
        assertEquals("v2", r.state.value.lists!!.version)
        assertEquals("\"cl-2\"", FileCodeListCache(cacheFile()).read()!!.etag)
    }

    @Test
    fun otherServerAddressDoesNotUseCachedListsOrEtag() = runBlocking {
        repo(FakeSource(fresh(rawV1, "\"cl-1\""))).refresh(url, force = true)
        val src = FakeSource(offline)
        val r = repo(src)
        r.loadCached("http://other.example.invalid/api/v1")
        assertNull(r.state.value.lists)
        r.refresh("http://other.example.invalid/api/v1")
        assertEquals(listOf<String?>(null), src.sentEtags)
    }

    @Test
    fun codeObjectsRoundTripExactly() = runBlocking {
        repo(FakeSource(fresh(rawV1, "\"cl-1\""))).refresh(url, force = true)
        // The device keeps the body byte for byte.
        assertEquals(rawV1, FileCodeListCache(cacheFile()).read()!!.raw)

        val r = repo(FakeSource())
        r.loadCached(url)
        val parts = r.state.value.lists!!.parts
        assertEquals(listOf(null to "07", "COMP-MECH" to "0063", null to "99 – JSIOC"), parts.map { it.group to it.code })
        // What goes back to the server: group + code exactly as received (leading zeros, case, spaces).
        val out = parts.map { ApiClient.writeJson.encodeToString(CodeKey.serializer(), it.key) }
        assertEquals(listOf("""{"group":null,"code":"07"}""", """{"group":"COMP-MECH","code":"0063"}""",
            """{"group":null,"code":"99 – JSIOC"}"""), out)
        val lists = r.state.value.lists!!
        assertEquals(listOf(CodeKey(null, "50")), lists.normalIssueCodes)
        assertTrue(lists.issues.first { it.code == "50" }.pinFirst)
    }

    @Test
    fun apiClientSendsIfNoneMatchAndHandles304() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(rawV1).setHeader("ETag", "\"cl-1\""))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"cl-1\""))
        server.start()
        try {
            val base = server.url("/api/v1").toString().trimEnd('/')
            val r = CodeListRepository(ApiClient().asCodeListSource(), FileCodeListCache(cacheFile()), clock = { now })
            assertEquals(RefreshOutcome.UPDATED, r.refresh(base, force = true))
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertEquals(RefreshOutcome.NOT_MODIFIED, r.refresh(base, force = true))
            val second = server.takeRequest()
            assertEquals("/api/v1/codelists", second.path)
            assertEquals("\"cl-1\"", second.getHeader("If-None-Match"))
            assertEquals("v1", r.state.value.lists!!.version)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun conditionalCallSendsSignInHeadersAndReports401() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(304))
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":{"code":"UNAUTHENTICATED","message":"Please sign in again.","retryable":false}}"""))
        server.start()
        try {
            var expired: String? = null
            val api = ApiClient(tokenProvider = { "tok-1" }, onUnauthorized = { expired = it }, appVersion = "0.1.9",
                deviceIdProvider = { "dev-1" })
            val base = server.url("/api/v1").toString().trimEnd('/')
            assertEquals(ApiResult.Ok(CodeListFetch.NotModified), api.codeListsConditional(base, "\"cl-1\""))
            val req = server.takeRequest()
            assertEquals("Bearer tok-1", req.getHeader("Authorization"))
            assertEquals("0.1.9", req.getHeader("X-App-Version"))
            assertEquals("dev-1", req.getHeader("X-Device-Id"))
            assertEquals("\"cl-1\"", req.getHeader("If-None-Match"))

            val r = api.codeListsConditional(base, null) as ApiResult.Failure
            assertEquals("UNAUTHENTICATED", r.code)
            assertEquals("tok-1", expired)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun recentCodesNewestFirstDedupedAndPersisted() {
        val f = File(tmp.root, "codelists/recent.json")
        val s = RecentCodesStore(f, max = 3)
        listOf("13", "07", "13", "48a", "50").forEach { s.record("issues", CodeKey(null, it)) }
        assertEquals(listOf("50", "48a", "13"), s.get("issues").map { it.code })
        assertEquals(listOf("50", "48a", "13"), RecentCodesStore(f, max = 3).get("issues").map { it.code })
        assertTrue(s.get("parts").isEmpty())
    }
}
