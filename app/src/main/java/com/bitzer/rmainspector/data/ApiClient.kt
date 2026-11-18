package com.bitzer.rmainspector.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of a server call. Failures carry a message that is safe to show the inspector. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failure(
        val kind: Kind,
        val message: String,
        val code: String? = null,
        val httpStatus: Int? = null,
        val retryable: Boolean = false,
        /** The error envelope's `details` (e.g. `current` + `current_signature` of `REPORT_PICS_CHANGED`). */
        val details: JsonObject? = null,
    ) : ApiResult<Nothing>

    enum class Kind { UNREACHABLE, SERVER_ERROR, BAD_RESPONSE }
}

/**
 * Talks to the RMA app server (docs/api.md). URLs are built with OkHttp's HttpUrl so opaque ids such as
 * `rma_id` are always percent-encoded — the tablet never assumes what an id looks like.
 *
 * Every call carries `Authorization: Bearer <token>` when signed in (api §1, §3), plus `X-App-Version` and
 * `X-Device-Id`. A `401` on a call that carried a token reports that token to [onUnauthorized] so the app
 * can return to sign-in (queued work is kept).
 */
class ApiClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson,
    private val tokenProvider: () -> String? = { null },
    private val onUnauthorized: (rejectedToken: String) -> Unit = {},
    private val appVersion: String = "",
    private val deviceIdProvider: () -> String? = { null },
) {
    suspend fun health(baseUrl: String): ApiResult<Health> =
        get(url(baseUrl, "health"), baseUrl, Health.serializer())

    /** `POST /auth/login` (api §3). Wrong credentials → [WRONG_CREDENTIALS] as the message. */
    suspend fun login(baseUrl: String, username: String, password: String, deviceId: String): ApiResult<LoginResponse> {
        val body = writeJson.encodeToString(LoginRequest.serializer(), LoginRequest(username.trim(), password, deviceId))
        return when (val r = send(url(baseUrl, "auth", "login"), baseUrl, LoginResponse.serializer(), body.toRequestBody(JSON_TYPE), authorized = false)) {
            is ApiResult.Failure ->
                if (r.code == "INVALID_CREDENTIALS" || r.httpStatus == 401) r.copy(message = WRONG_CREDENTIALS) else r
            is ApiResult.Ok -> r
        }
    }

    /** `POST /auth/logout` with an explicit token (the app has usually forgotten it already). Best effort. */
    suspend fun logout(baseUrl: String, token: String): ApiResult<Unit> =
        send(url(baseUrl, "auth", "logout"), baseUrl, null, ByteArray(0).toRequestBody(null), authorized = false, explicitToken = token)

    suspend fun me(baseUrl: String): ApiResult<User> =
        get(url(baseUrl, "me"), baseUrl, User.serializer())

    suspend fun codeLists(baseUrl: String): ApiResult<CodeLists> =
        get(url(baseUrl, "codelists"), baseUrl, CodeLists.serializer())

    /**
     * `/codelists` with `If-None-Match` (api §4). Returns the raw body so the cache keeps exactly what the server
     * sent; `304` → [CodeListFetch.NotModified].
     */
    suspend fun codeListsConditional(baseUrl: String, etag: String?): ApiResult<CodeListFetch> = withContext(Dispatchers.IO) {
        val url = url(baseUrl, "codelists")
            ?: return@withContext ApiResult.Failure(ApiResult.Kind.UNREACHABLE, "The server address isn't valid. Check it in Settings.")
        val token = tokenProvider()
        val request = requestBuilder(url, token).apply {
            if (!etag.isNullOrEmpty()) header("If-None-Match", etag)
        }.build()
        try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 304 -> ApiResult.Ok(CodeListFetch.NotModified)
                    resp.isSuccessful -> try {
                        val parsed = json.decodeFromString(CodeLists.serializer(), body)
                        ApiResult.Ok(CodeListFetch.Fresh(body, resp.header("ETag") ?: parsed.etag.ifEmpty { null }, parsed))
                    } catch (e: Exception) {
                        ApiResult.Failure(ApiResult.Kind.BAD_RESPONSE, "The server sent code lists the app doesn't understand.", httpStatus = resp.code)
                    }
                    else -> errorResult(resp.code, body, token, reportUnauthorized = true)
                }
            }
        } catch (e: IOException) {
            unreachable(baseUrl)
        }
    }

    /** Free-text search; the server decides whether it's an RMA, a serial, … (api §5). */
    suspend fun search(baseUrl: String, query: String): ApiResult<SearchResponse> =
        get(url(baseUrl, "rmas", "search", query = mapOf("q" to query.trim())), baseUrl, SearchResponse.serializer())

    /**
     * Work list (api §5). [productTypes] / [returnCodes] = the selected chips; null = leave the parameter out
     * (server default). An empty list sends an empty value, which matches nothing.
     */
    suspend fun workList(
        baseUrl: String,
        productTypes: List<String>?,
        returnCodes: List<CodeKey>?,
        limit: Int = 200,
    ): ApiResult<WorkListResponse> {
        val q = buildMap {
            if (productTypes != null) put("product_type", productTypes.joinToString(","))
            if (returnCodes != null) put("return_code", CodeQuery.format(returnCodes))
            put("limit", limit.toString())
        }
        return get(url(baseUrl, "rmas", query = q), baseUrl, WorkListResponse.serializer())
    }

    suspend fun rmaDetail(baseUrl: String, rmaId: String): ApiResult<RmaDetail> =
        get(url(baseUrl, "rmas", rmaId), baseUrl, RmaDetail.serializer())

    /** `GET /rmas/{id}/photos` (api §8): All Pics with part links, plus the `Report Pics ` folder. */
    suspend fun photoListing(baseUrl: String, rmaId: String): ApiResult<PhotoListing> =
        get(url(baseUrl, "rmas", rmaId, "photos"), baseUrl, PhotoListing.serializer())

    /** `PUT /rmas/{id}/report-pics` (api §9.2). `409 REPORT_PICS_CHANGED` comes back as a failure with details. */
    suspend fun putReportPics(baseUrl: String, rmaId: String, request: ReportPicsRequest): ApiResult<ReportPicsSaved> {
        val body = writeJson.encodeToString(ReportPicsRequest.serializer(), request).toRequestBody(JSON_TYPE)
        return send(url(baseUrl, "rmas", rmaId, "report-pics"), baseUrl, ReportPicsSaved.serializer(), body, httpMethod = "PUT")
    }

    /** `GET /rmas/{id}/photos/{all|report}/{filename}?size=thumb|full` (api §8) — the JPEG bytes. */
    suspend fun photoBytes(baseUrl: String, rmaId: String, folder: String, filename: String, thumb: Boolean): ApiResult<ByteArray> =
        withContext(Dispatchers.IO) {
            val url = url(baseUrl, "rmas", rmaId, "photos", folder, filename, query = mapOf("size" to if (thumb) "thumb" else "full"))
                ?: return@withContext ApiResult.Failure(ApiResult.Kind.UNREACHABLE, "The server address isn't valid. Check it in Settings.")
            val token = tokenProvider()
            try {
                http.newCall(requestBuilder(url, token).header("Accept", "image/jpeg").build()).execute().use { resp ->
                    if (resp.isSuccessful) ApiResult.Ok(resp.body?.bytes() ?: ByteArray(0))
                    else errorResult(resp.code, resp.body?.string().orEmpty(), token, reportUnauthorized = true)
                }
            } catch (e: IOException) {
                unreachable(baseUrl)
            }
        }

    suspend fun <T> get(
        url: HttpUrl?,
        baseUrl: String,
        deserializer: DeserializationStrategy<T>,
    ): ApiResult<T> = send(url, baseUrl, deserializer, body = null)

    /**
     * One request. [body] null = GET, otherwise POST. [deserializer] null = ignore the response body (e.g. 204).
     * [authorized] false = no stored token is sent (login); [explicitToken] overrides the stored token.
     */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun <T> send(
        url: HttpUrl?,
        baseUrl: String,
        deserializer: DeserializationStrategy<T>?,
        body: RequestBody?,
        authorized: Boolean = true,
        explicitToken: String? = null,
        httpMethod: String = "POST",
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (url == null) {
            return@withContext ApiResult.Failure(ApiResult.Kind.UNREACHABLE, "The server address isn't valid. Check it in Settings.")
        }
        val token = explicitToken ?: if (authorized) tokenProvider() else null
        val request = requestBuilder(url, token).apply { if (body != null) method(httpMethod, body) }.build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    if (deserializer == null) return@use ApiResult.Ok(Unit as T)
                    try {
                        ApiResult.Ok(json.decodeFromString(deserializer, text))
                    } catch (e: Exception) {
                        ApiResult.Failure(ApiResult.Kind.BAD_RESPONSE, "The server sent an answer the app doesn't understand. Is this the RMA app server?", httpStatus = resp.code)
                    }
                } else {
                    errorResult(resp.code, text, token, reportUnauthorized = explicitToken == null)
                }
            }
        } catch (e: IOException) {
            unreachable(baseUrl)
        }
    }

    /** Headers every call carries (api §1): Accept, X-App-Version, X-Device-Id and the bearer token if any. */
    private fun requestBuilder(url: HttpUrl, token: String?): Request.Builder = Request.Builder().url(url).apply {
        header("Accept", "application/json")
        if (appVersion.isNotEmpty()) header("X-App-Version", appVersion)
        deviceIdProvider()?.let { header("X-Device-Id", it) }
        if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token")
    }

    /** Error envelope → friendly failure; a 401 for the stored token goes to [onUnauthorized]. */
    private fun errorResult(status: Int, text: String, token: String?, reportUnauthorized: Boolean): ApiResult.Failure {
        val err = runCatching { json.decodeFromString(ApiErrorEnvelope.serializer(), text).error }.getOrNull()
        if (status == 401 && !token.isNullOrEmpty() && reportUnauthorized) onUnauthorized(token)
        return ApiResult.Failure(
            ApiResult.Kind.SERVER_ERROR,
            err?.message ?: "The server answered with error $status.",
            code = err?.code,
            httpStatus = status,
            retryable = err?.retryable ?: (status >= 500),
            details = err?.details,
        )
    }

    private fun unreachable(baseUrl: String) = ApiResult.Failure(
        ApiResult.Kind.UNREACHABLE,
        "Can't reach the server at $baseUrl. Check the address in Settings and that this device is on the same network.",
        retryable = true,
    )

    companion object {
        /** Shown on the sign-in screen for `401 INVALID_CREDENTIALS` (issue #16 acceptance: exactly this text). */
        const val WRONG_CREDENTIALS = "Wrong username or password."
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Reading: tolerant of new/missing fields. */
        val defaultJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

        /** Writing: every field always present, nulls written as null — e.g. {"group": null, "code": "48a"}. */
        val writeJson = Json { encodeDefaults = true; explicitNulls = true }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** base + path segments (each percent-encoded) + query parameters. Null if the base isn't a URL. */
        fun url(baseUrl: String, vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl? {
            val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            return base.newBuilder().apply {
                segments.forEach { addPathSegment(it) }
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
        }
    }
}

/** Outcome of a conditional `/codelists` call. */
sealed interface CodeListFetch {
    data object NotModified : CodeListFetch
    /** [raw] is the body exactly as received; it is what gets cached. */
    data class Fresh(val raw: String, val etag: String?, val lists: CodeLists) : CodeListFetch
}

/** Code list query value (e.g. `return_code`): comma-separated codes; `group:code` when a group is present (api §5). */
object CodeQuery {
    fun format(statuses: List<CodeKey>): String =
        statuses.joinToString(",") { if (it.group.isNullOrEmpty()) it.code else "${it.group}:${it.code}" }
}
