package com.bitzer.rmainspector.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
    ) : ApiResult<Nothing>

    enum class Kind { UNREACHABLE, SERVER_ERROR, BAD_RESPONSE }
}

/**
 * Talks to the RMA app server (docs/api.md). URLs are built with OkHttp's HttpUrl so opaque ids such as
 * `rma_id` are always percent-encoded — the tablet never assumes what an id looks like.
 */
class ApiClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson,
) {
    suspend fun health(baseUrl: String): ApiResult<Health> =
        get(url(baseUrl, "health"), baseUrl, Health.serializer())

    suspend fun inspectors(baseUrl: String): ApiResult<InspectorsResponse> =
        get(url(baseUrl, "inspectors"), baseUrl, InspectorsResponse.serializer())

    suspend fun codeLists(baseUrl: String): ApiResult<CodeLists> =
        get(url(baseUrl, "codelists"), baseUrl, CodeLists.serializer())

    /** Free-text search; the server decides whether it's an RMA, a serial, … (api §5). */
    suspend fun search(baseUrl: String, query: String): ApiResult<SearchResponse> =
        get(url(baseUrl, "rmas", "search", query = mapOf("q" to query.trim())), baseUrl, SearchResponse.serializer())

    /** Work list. Empty [statuses] = server default. */
    suspend fun workList(baseUrl: String, statuses: List<CodeKey>, limit: Int = 50): ApiResult<WorkListResponse> {
        val q = buildMap {
            if (statuses.isNotEmpty()) put("inspection_status", StatusQuery.format(statuses))
            put("limit", limit.toString())
        }
        return get(url(baseUrl, "rmas", query = q), baseUrl, WorkListResponse.serializer())
    }

    suspend fun rmaDetail(baseUrl: String, rmaId: String): ApiResult<RmaDetail> =
        get(url(baseUrl, "rmas", rmaId), baseUrl, RmaDetail.serializer())

    suspend fun <T> get(
        url: HttpUrl?,
        baseUrl: String,
        deserializer: DeserializationStrategy<T>,
        inspector: String? = null,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (url == null) {
            return@withContext ApiResult.Failure(ApiResult.Kind.UNREACHABLE, "The server address isn't valid. Check it in Settings.")
        }
        val request = Request.Builder().url(url).apply {
            header("Accept", "application/json")
            if (inspector != null) header("X-Inspector", inspector)
        }.build()
        try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    try {
                        ApiResult.Ok(json.decodeFromString(deserializer, body))
                    } catch (e: Exception) {
                        ApiResult.Failure(ApiResult.Kind.BAD_RESPONSE, "The server sent an answer the app doesn't understand. Is this the RMA app server?", httpStatus = resp.code)
                    }
                } else {
                    val err = runCatching { json.decodeFromString(ApiErrorEnvelope.serializer(), body).error }.getOrNull()
                    ApiResult.Failure(
                        ApiResult.Kind.SERVER_ERROR,
                        err?.message ?: "The server answered with error ${resp.code}.",
                        code = err?.code,
                        httpStatus = resp.code,
                        retryable = err?.retryable ?: (resp.code >= 500),
                    )
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(
                ApiResult.Kind.UNREACHABLE,
                "Can't reach the server at $baseUrl. Check the address in Settings and that this device is on the same network.",
                retryable = true,
            )
        }
    }

    companion object {
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

/** `inspection_status` query value: comma-separated codes; `group:code` when a group is present (api §5). */
object StatusQuery {
    fun format(statuses: List<CodeKey>): String =
        statuses.joinToString(",") { if (it.group.isNullOrEmpty()) it.code else "${it.group}:${it.code}" }
}
