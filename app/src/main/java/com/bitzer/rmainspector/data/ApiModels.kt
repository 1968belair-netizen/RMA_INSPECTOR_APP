package com.bitzer.rmainspector.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Shapes from docs/api.md. Unknown fields are ignored so the server can add fields safely.

@Serializable
data class Health(
    val status: String,
    @SerialName("api_version") val apiVersion: String = "",
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("min_app_version") val minAppVersion: String = "0",
    val backend: String = "",
    @SerialName("macola_reachable") val macolaReachable: Boolean = false,
    @SerialName("fileshare_reachable") val fileshareReachable: Boolean = false,
    @SerialName("server_time") val serverTime: String = "",
)

@Serializable
data class Inspector(
    val username: String,
    @SerialName("display_name") val displayName: String = username,
    val initials: String = "",
    @SerialName("audit_user_id") val auditUserId: String = "",
)

@Serializable
data class InspectorsResponse(
    val inspectors: List<Inspector> = emptyList(),
    @SerialName("max_inspectors") val maxInspectors: Int = 10,
    @SerialName("sign_in_required") val signInRequired: Boolean = false,
)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)
