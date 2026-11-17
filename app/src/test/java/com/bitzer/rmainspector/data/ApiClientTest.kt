package com.bitzer.rmainspector.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Uses example JSON from docs/api.md (fake data). */
class ApiClientTest {
    private lateinit var server: MockWebServer
    private val api = ApiClient()
    private val base get() = server.url("/api/v1").toString().trimEnd('/')

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    @Test
    fun parsesHealthAndIgnoresUnknownFields() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"status":"ok","api_version":"1.0","server_version":"0.1.0","min_app_version":"0.1.0","backend":"mock",
               "macola_reachable":true,"fileshare_reachable":true,"server_time":"2026-11-17T10:40:00-05:00","new_field":1}"""))
        val r = api.health(base)
        assertTrue(r is ApiResult.Ok)
        assertEquals("mock", (r as ApiResult.Ok).value.backend)
        assertEquals("/api/v1/health", server.takeRequest().path)
    }

    @Test
    fun parsesInspectors() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"inspectors":[{"username":"jdoe","display_name":"J. Doe","initials":"JD","audit_user_id":"JDOE_SQL"}],
               "max_inspectors":10,"sign_in_required":false}"""))
        val r = api.inspectors(base) as ApiResult.Ok
        assertEquals("JD", r.value.inspectors.single().initials)
    }

    @Test
    fun mapsErrorEnvelopeToFriendlyMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(423).setBody(
            """{"error":{"code":"RMA_LOCKED","message":"RMA 912345 is open in Macola by JDOE2. Nothing was saved.",
               "retryable":true,"retry_after_seconds":120,"details":{"held_by":"JDOE2"}}}"""))
        val r = api.health(base) as ApiResult.Failure
        assertEquals("RMA_LOCKED", r.code)
        assertEquals(423, r.httpStatus)
        assertTrue(r.retryable)
        assertTrue(r.message.startsWith("RMA 912345 is open"))
    }

    @Test
    fun unreachableServerGivesHelpfulMessage() = runBlocking {
        val dead = base
        server.shutdown()
        val r = api.health(dead) as ApiResult.Failure
        assertEquals(ApiResult.Kind.UNREACHABLE, r.kind)
        assertTrue(r.message.contains("Can't reach the server"))
    }

    @Test
    fun nonJsonAnswerIsReportedClearly() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>router login</html>"))
        val r = api.health(base) as ApiResult.Failure
        assertEquals(ApiResult.Kind.BAD_RESPONSE, r.kind)
    }
}
