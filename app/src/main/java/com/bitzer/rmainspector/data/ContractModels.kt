package com.bitzer.rmainspector.data

import com.bitzer.rmainspector.core.NaturalSort
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A code as the server sent it (docs/api.md §1.1). `group` is null for Macola; SAP catalogs use code group +
 * code. Never trim, pad, recase or retype — send back [key] exactly as received.
 */
@Serializable
data class Code(
    val group: String? = null,
    val code: String,
    val description: String = "",
) {
    val key: CodeKey get() = CodeKey(group, code)
    val label: String get() = if (description.isBlank()) code else "$code  $description"
}

/** What the tablet writes back: group + code only, both always present (group may be null). */
@Serializable
data class CodeKey(val group: String?, val code: String)

/** Natural order: group first (null groups first), then code — `48` < `48a` < `100`. */
object CodeOrder : Comparator<Code> {
    override fun compare(a: Code, b: Code): Int {
        val g = when {
            a.group == b.group -> 0
            a.group == null -> -1
            b.group == null -> 1
            else -> NaturalSort.compare(a.group, b.group)
        }
        return if (g != 0) g else NaturalSort.compare(a.code, b.code)
    }
}

@Serializable
data class SummaryBlockDef(
    @SerialName("block_id") val blockId: String,
    val label: String,
    @SerialName("max_length") val maxLength: Int,
)

@Serializable
data class Capabilities(
    val findings: Boolean = false,
    val summary: Boolean = false,
    val photos: Boolean = false,
    val status: Boolean = false,
    val report: Boolean = false,
)

@Serializable
data class ProductType(val value: String, val key: String, val capabilities: Capabilities = Capabilities())

@Serializable
data class CodeLists(
    val version: String = "",
    val etag: String = "",
    val source: String = "",
    val parts: List<Code> = emptyList(),
    val issues: List<Code> = emptyList(),
    val causes: List<Code> = emptyList(),
    @SerialName("defect_codes") val defectCodes: List<Code> = emptyList(),
    @SerialName("inspection_statuses") val inspectionStatuses: List<Code> = emptyList(),
    @SerialName("status_rule") val statusRule: String = "any",
    @SerialName("summary_blocks") val summaryBlocks: List<SummaryBlockDef> = emptyList(),
    @SerialName("product_types") val productTypes: List<ProductType> = emptyList(),
    @SerialName("work_list_statuses") val workListStatuses: List<CodeKey> = emptyList(),
    @SerialName("photo_tags") val photoTags: List<String> = emptyList(),
    @SerialName("summary_template") val summaryTemplate: String = "",
    val limits: Map<String, Int> = emptyMap(),
) {
    /** The cause picker is shown only when the server sends causes (none for Macola). */
    val causesEnabled: Boolean get() = causes.isNotEmpty()
}

@Serializable
data class RmaListItem(
    @SerialName("rma_id") val rmaId: String,
    @SerialName("display_number") val displayNumber: String,
    val model: String = "",
    @SerialName("failed_serial_no") val failedSerialNo: String = "",
    @SerialName("product_type") val productType: String = "",
    @SerialName("product_type_key") val productTypeKey: String = "unknown",
    @SerialName("inspection_status") val inspectionStatus: Code? = null,
    @SerialName("is_open") val isOpen: Boolean = false,
    val inspectable: Boolean = false,
    val locked: Boolean = false,
)

@Serializable
data class SearchResponse(
    val query: String = "",
    @SerialName("matched_on") val matchedOn: String = "none",
    val results: List<RmaListItem> = emptyList(),
)

@Serializable
data class RmaHeader(
    @SerialName("customer_name") val customerName: String = "",
    val model: String = "",
    @SerialName("failed_serial_no") val failedSerialNo: String = "",
    @SerialName("sales_order_no") val salesOrderNo: String = "",
    @SerialName("return_code") val returnCode: String = "",
    @SerialName("inspection_status") val inspectionStatus: Code? = null,
    @SerialName("credit_status") val creditStatus: String = "",
    @SerialName("failed_date") val failedDate: String = "",
    @SerialName("rma_comment") val rmaComment: String = "",
    @SerialName("failure_code") val failureCode: String = "",
    @SerialName("failure_description") val failureDescription: String = "",
    val comments: String = "",
)

@Serializable
data class LockState(
    val locked: Boolean = false,
    @SerialName("held_by") val heldBy: String? = null,
    val host: String? = null,
    @SerialName("possibly_stale") val possiblyStale: Boolean = false,
)

/** A parts row as read. [rowId] is opaque — echo it, never parse it. */
@Serializable
data class PartRow(
    @SerialName("row_id") val rowId: String,
    @SerialName("part_code") val partCode: Code,
    @SerialName("issue_code") val issueCode: Code,
    @SerialName("cause_code") val causeCode: Code? = null,
    val note: String = "",
)

/** A parts row as written. New rows have rowId = null; existing rows left out of the list are deleted. */
@Serializable
data class PartRowWrite(
    @SerialName("row_id") val rowId: String?,
    @SerialName("part_code") val partCode: CodeKey,
    @SerialName("issue_code") val issueCode: CodeKey,
    @SerialName("cause_code") val causeCode: CodeKey?,
    val note: String,
)

fun PartRow.toWrite(): PartRowWrite = PartRowWrite(rowId, partCode.key, issueCode.key, causeCode?.key, note)

@Serializable
data class SummaryBlock(@SerialName("block_id") val blockId: String, val text: String = "")

@Serializable
data class Summary(
    val exists: Boolean = false,
    @SerialName("defect_code") val defectCode: Code? = null,
    val blocks: List<SummaryBlock> = emptyList(),
    @SerialName("inspected_by") val inspectedBy: String = "",
    @SerialName("summary_by") val summaryBy: String = "",
)

@Serializable
data class PhotoFolder(
    @SerialName("folder_found") val folderFound: Boolean = false,
    @SerialName("folder_display") val folderDisplay: String = "",
    val count: Int = 0,
    val files: List<String> = emptyList(),
)

@Serializable
data class RmaDetail(
    @SerialName("rma_id") val rmaId: String,
    @SerialName("display_number") val displayNumber: String,
    val signature: String,
    @SerialName("is_open") val isOpen: Boolean = false,
    val inspectable: Boolean = false,
    @SerialName("product_type") val productType: String = "",
    @SerialName("product_type_key") val productTypeKey: String = "unknown",
    @SerialName("product_type_source") val productTypeSource: String = "header",
    val capabilities: Capabilities = Capabilities(),
    val header: RmaHeader = RmaHeader(),
    val lock: LockState = LockState(),
    val parts: List<PartRow> = emptyList(),
    val summary: Summary = Summary(),
    val photos: PhotoFolder = PhotoFolder(),
)
