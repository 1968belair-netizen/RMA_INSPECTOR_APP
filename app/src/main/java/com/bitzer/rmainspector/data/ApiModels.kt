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

/** The signed-in user (api §3). Initials pre-fill Inspected by / Summary by. */
@Serializable
data class User(
    val username: String,
    @SerialName("display_name") val displayName: String = username,
    val initials: String = "",
    @SerialName("audit_user_id") val auditUserId: String = "",
    val roles: List<String> = emptyList(),
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    val user: User,
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
