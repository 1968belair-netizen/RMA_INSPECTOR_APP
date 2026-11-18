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
               "backend_reachable":true,"photo_store_reachable":true,"server_time":"2026-11-17T10:40:00-05:00","new_field":1}"""))
        val r = api.health(base)
        assertTrue(r is ApiResult.Ok)
        assertEquals("mock", (r as ApiResult.Ok).value.backend)
        assertEquals("/api/v1/health", server.takeRequest().path)
    }

    @Test
    fun loginParsesTokenAndUser() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"token":"opaque-token-example","expires_at":null,
               "user":{"username":"jdoe","display_name":"J. Doe","initials":"JD","audit_user_id":"JDOE_SQL","roles":["all"]}}"""))
        val r = api.login(base, " jdoe ", "pw", "dev-1") as ApiResult.Ok
        assertEquals("opaque-token-example", r.value.token)
        assertEquals("JD", r.value.user.initials)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/auth/login", req.path)
        assertEquals(null, req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"username\":\"jdoe\"") && body.contains("\"device_id\":\"dev-1\""))
    }

    @Test
    fun wrongPasswordShowsExactMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(
            """{"error":{"code":"INVALID_CREDENTIALS","message":"Bad login.","retryable":false,"details":{}}}"""))
        var expired: String? = null
        val client = ApiClient(tokenProvider = { "old" }, onUnauthorized = { expired = it })
        val r = client.login(base, "demo", "nope", "dev-1") as ApiResult.Failure
        assertEquals("Wrong username or password.", r.message)
        assertEquals(null, expired) // a failed sign-in never signs anyone out
    }

    @Test
    fun sendsBearerTokenAndReports401() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"username":"demo"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody(
            """{"error":{"code":"UNAUTHENTICATED","message":"Please sign in.","retryable":false,"details":{}}}"""))
        var expired: String? = null
        val client = ApiClient(tokenProvider = { "tok-1" }, onUnauthorized = { expired = it }, appVersion = "0.1.9", deviceIdProvider = { "dev-1" })
        assertTrue(client.me(base) is ApiResult.Ok)
        val req = server.takeRequest()
        assertEquals("Bearer tok-1", req.getHeader("Authorization"))
        assertEquals("0.1.9", req.getHeader("X-App-Version"))
        assertEquals("dev-1", req.getHeader("X-Device-Id"))
        assertEquals(null, req.getHeader("X-Inspector"))
        val r = client.me(base) as ApiResult.Failure
        assertEquals(401, r.httpStatus)
        assertEquals("tok-1", expired)
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
