package com.bitzer.rmainspector.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** `200` of `POST /rmas/{id}/inspection` (api §7). [parts] are in saved order with the new `row_id`s. */
@Serializable
data class InspectionSaved(
    val result: String = "",
    @SerialName("submission_id") val submissionId: String = "",
    val replayed: Boolean = false,
    @SerialName("rma_id") val rmaId: String = "",
    @SerialName("new_signature") val newSignature: String = "",
    val verified: Boolean = true,
    val parts: List<SavedPart> = emptyList(),
    val warnings: List<SubmitWarning> = emptyList(),
)

@Serializable
data class SavedPart(
    @SerialName("row_id") val rowId: String,
    @SerialName("part_code") val partCode: Code? = null,
    @SerialName("issue_code") val issueCode: Code? = null,
)

@Serializable
data class SubmitWarning(val code: String = "", val message: String = "")

/** `201` (or replayed `200`) of `POST /rmas/{id}/photos` (api §8). [filename] is the server's final name. */
@Serializable
data class PhotoUploaded(
    @SerialName("photo_id") val photoId: String = "",
    val replayed: Boolean = false,
    val filename: String = "",
)

/** Only what the tablet needs from `POST /rmas/{id}/report` (api §9.3). */
@Serializable
data class ReportRebuilt(val result: String = "")

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val JPEG_TYPE = "image/jpeg".toMediaType()

/** `POST /rmas/{id}/inspection` — [payload] is built by [SubmitPayload.build]. */
suspend fun ApiClient.submitInspection(baseUrl: String, rmaId: String, payload: JsonObject, token: String? = null): ApiResult<InspectionSaved> =
    send(ApiClient.url(baseUrl, "rmas", rmaId, "inspection"), baseUrl, InspectionSaved.serializer(),
        payload.toString().toRequestBody(JSON_TYPE), explicitToken = token)

/** `POST /rmas/{id}/photos` (multipart `meta` + `file`). `201` and a replayed `200` are both success. */
suspend fun ApiClient.uploadPhoto(baseUrl: String, rmaId: String, meta: PhotoMetaWrite, file: File, token: String? = null): ApiResult<PhotoUploaded> {
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("meta", ApiClient.writeJson.encodeToString(PhotoMetaWrite.serializer(), meta))
        .addFormDataPart("file", meta.photoId + ".jpg", file.asRequestBody(JPEG_TYPE))
        .build()
    return send(ApiClient.url(baseUrl, "rmas", rmaId, "photos"), baseUrl, PhotoUploaded.serializer(), body, explicitToken = token)
}

/** `POST /rmas/{id}/report` — regenerate PDF + XLSX after "Update inspection findings" (api §9.3). */
suspend fun ApiClient.rebuildReportAfterSubmit(baseUrl: String, rmaId: String, submissionId: String, token: String? = null): ApiResult<ReportRebuilt> {
    val json = """{"submission_id":"$submissionId","formats":["pdf","xlsx"]}"""
    return send(ApiClient.url(baseUrl, "rmas", rmaId, "report"), baseUrl, ReportRebuilt.serializer(), json.toRequestBody(JSON_TYPE), explicitToken = token)
}
