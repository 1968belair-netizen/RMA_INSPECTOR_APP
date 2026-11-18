package com.bitzer.rmainspector.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `report` block of `/codelists` (api §4). Pages ≈ [fixedPages] + ⌈photos ÷ [photosPerPage]⌉. */
@Serializable
data class ReportSettings(
    @SerialName("typical_photo_count") val typicalPhotoCount: Int = 20,
    @SerialName("photos_per_page") val photosPerPage: Int = 6,
    @SerialName("fixed_pages") val fixedPages: Int = 2,
)

/** One file of `All Pics` with its part link (api §8). `partCode`/`rowId` are null when unknown (old or desk photos). */
@Serializable
data class PhotoEntry(
    val filename: String,
    val bytes: Long = 0,
    val seq: Int? = null,
    @SerialName("part_code") val partCode: CodeKey? = null,
    @SerialName("row_id") val rowId: String? = null,
)

@Serializable
data class ReportPicsFolder(
    @SerialName("folder_found") val folderFound: Boolean = false,
    val signature: String = "",
    val files: List<String> = emptyList(),
)

/** `GET /rmas/{id}/photos` (api §8). */
@Serializable
data class PhotoListing(
    @SerialName("folder_found") val folderFound: Boolean = false,
    @SerialName("folder_display") val folderDisplay: String = "",
    @SerialName("all_pics") val allPics: List<PhotoEntry> = emptyList(),
    @SerialName("report_pics") val reportPics: ReportPicsFolder = ReportPicsFolder(),
)

/** One pick as sent to `PUT /report-pics`: [source] = `all_pics` or `report_pics` (api §9.2). */
@Serializable
data class ReportPickWrite(val source: String, val filename: String, val caption: String)

@Serializable
data class ReportPicsRequest(
    @SerialName("submission_id") val submissionId: String,
    @SerialName("base_signature") val baseSignature: String,
    val photos: List<ReportPickWrite>,
)

@Serializable
data class ReportPicsChanges(val copied: Int = 0, val renamed: Int = 0, val removed: Int = 0, val kept: Int = 0)

@Serializable
data class ReportPicsSaved(
    val result: String = "saved",
    val replayed: Boolean = false,
    @SerialName("new_signature") val newSignature: String = "",
    @SerialName("folder_created") val folderCreated: Boolean = false,
    val changes: ReportPicsChanges = ReportPicsChanges(),
    @SerialName("report_pics") val reportPics: List<String> = emptyList(),
)
