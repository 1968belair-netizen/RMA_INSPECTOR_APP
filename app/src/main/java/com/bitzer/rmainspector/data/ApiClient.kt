package com.bitzer.rmainspector.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
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

class ApiClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson,
) {
    suspend fun health(baseUrl: String): ApiResult<Health> = get(baseUrl, "/health", Health.serializer())

    suspend fun inspectors(baseUrl: String): ApiResult<InspectorsResponse> =
        get(baseUrl, "/inspectors", InspectorsResponse.serializer())

    suspend fun <T> get(
        baseUrl: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        inspector: String? = null,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(baseUrl.trimEnd('/') + path).apply {
                header("Accept", "application/json")
                if (inspector != null) header("X-Inspector", inspector)
            }.build()
        } catch (e: IllegalArgumentException) {
            return@withContext ApiResult.Failure(ApiResult.Kind.UNREACHABLE, "The server address isn't valid. Check it in Settings.")
        }
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
        val defaultJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
