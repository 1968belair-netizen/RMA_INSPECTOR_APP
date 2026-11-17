package com.bitzer.rmainspector.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Shapes from docs/api.md (rev 6, SAP-ready). Unknown fields are ignored so the server can add fields safely.
// The tablet has no backend-specific rules: ids are opaque, codes are echoed exactly as received.

@Serializable
data class Health(
    val status: String,
    @SerialName("api_version") val apiVersion: String = "",
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("min_app_version") val minAppVersion: String = "0",
    val backend: String = "",
    @SerialName("backend_reachable") val backendReachable: Boolean = false,
    @SerialName("photo_store_reachable") val photoStoreReachable: Boolean = false,
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
