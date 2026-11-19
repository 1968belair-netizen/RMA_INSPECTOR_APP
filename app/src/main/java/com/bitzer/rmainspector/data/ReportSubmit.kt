package com.bitzer.rmainspector.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// Submit report (C2): spell check -> build -> PDF preview. Models of api §9.3-9.7 plus the pure rules the flow uses.

/** One flagged word of `POST /spellcheck` (api §9.6). */
@Serializable
data class SpellIssue(val word: String, val start: Int = 0, val end: Int = 0, val suggestions: List<String> = emptyList())

@Serializable
data class SpellcheckRequest(val text: String)

@Serializable
data class SpellcheckResponse(val issues: List<SpellIssue> = emptyList())

@Serializable
data class DictionaryRequest(val word: String)

@Serializable
data class DictionaryAdded(val word: String = "", val added: Boolean = true)

/** `POST /rmas/{id}/report` (api §9.3). */
@Serializable
data class BuildRequest(
    @SerialName("submission_id") val submissionId: String,
    val formats: List<String> = listOf("pdf", "xlsx"),
)

@Serializable
data class BuiltFile(val format: String, val filename: String = "", val bytes: Long = 0, val pages: Int? = null)

@Serializable
data class BuildWarning(val code: String, val message: String = "")

@Serializable
data class StatusChange(val from: CodeKey? = null, val to: CodeKey? = null, val audited: Boolean = false)

@Serializable
data class BuildResponse(
    val result: String = "built",
    val replayed: Boolean = false,
    @SerialName("generated_at") val generatedAt: String = "",
    val files: List<BuiltFile> = emptyList(),
    @SerialName("photo_count") val photoCount: Int = 0,
    @SerialName("previous_backed_up") val previousBackedUp: Boolean = false,
    @SerialName("status_changed") val statusChanged: StatusChange? = null,
    val warnings: List<BuildWarning> = emptyList(),
) {
    val pdf: BuiltFile? get() = files.firstOrNull { it.format == "pdf" }
}

/** Summary part of `POST /rmas/{id}/inspection` (api §7): every field is sent, the server compares. */
@Serializable
data class SummaryWriteBlock(@SerialName("block_id") val blockId: String, val text: String)

@Serializable
data class SummaryWrite(
    @SerialName("defect_code") val defectCode: CodeKey? = null,
    val blocks: List<SummaryWriteBlock> = emptyList(),
    @SerialName("inspected_by") val inspectedBy: String = "",
    @SerialName("summary_by") val summaryBy: String = "",
)

/** Summary-only save: status and parts stay unchanged (`status_change` and `parts` null). */
@Serializable
data class SummaryOnlyRequest(
    @SerialName("submission_id") val submissionId: String,
    @SerialName("base_signature") val baseSignature: String,
    @SerialName("product_type_key") val productTypeKey: String,
    @SerialName("status_change") val statusChange: StatusChange? = null,
    val summary: SummaryWrite,
    val parts: List<String>? = null,
)

@Serializable
data class SummarySaved(
    val result: String = "saved",
    val replayed: Boolean = false,
    @SerialName("new_signature") val newSignature: String = "",
)

/** What the Report tab's summary fields hold now, in the shape of the inspection request. */
fun ReportDraft.toSummaryWrite() = SummaryWrite(
    defectCode = defectCode?.key,
    blocks = summaries.map { (id, text) -> SummaryWriteBlock(id, text) }.sortedBy { it.blockId },
    inspectedBy = inspectedBy.trim(),
    summaryBy = summaryBy.trim(),
)

/** Status rules of the submit dialogs (decision 2026-11-18, spec §6). Names default to the Macola workflow. */
enum class StatusWarning { NONE, ALREADY_COMPLETE, ALREADY_SENT }

object ReportSubmitRules {
    const val REPORT_COMPLETE = "Report Complete"
    val REPORT_SENT = setOf("Sent to RSM", "Issued to Customer")

    const val SENT_WARNING = "This report was already sent. Updating will regenerate it; you may need to resend it."
    const val COMPLETE_NOTE = "The report was already built. Updating will regenerate it; the status stays Report Complete."

    /** Dialog to show before the flow starts, from the RMA's current inspection status code. */
    fun warningFor(statusCode: String?): StatusWarning = when {
        statusCode == null -> StatusWarning.NONE
        statusCode in REPORT_SENT -> StatusWarning.ALREADY_SENT
        statusCode == REPORT_COMPLETE -> StatusWarning.ALREADY_COMPLETE
        else -> StatusWarning.NONE
    }

    /** Does the draft differ from the summary on the server (so it must be saved before the build)? */
    fun summaryChanged(draft: ReportDraft, server: Summary): Boolean {
        if (draft.defectCode?.key != server.defectCode?.key) return true
        val serverBlocks = server.blocks.associate { it.blockId to it.text }
        if (draft.summaries.any { (id, text) -> text != serverBlocks[id].orEmpty() }) return true
        return draft.inspectedBy.trim() != server.inspectedBy.trim() || draft.summaryBy.trim() != server.summaryBy.trim()
    }

    /** The server summary after a successful save of [draft] (so a retry does not send it again). */
    fun summaryAfterSave(draft: ReportDraft, server: Summary): Summary {
        val ids = (server.blocks.map { it.blockId } + draft.summaries.keys).distinct()
        return Summary(
            exists = true,
            defectCode = draft.defectCode,
            blocks = ids.map { SummaryBlock(it, draft.summaries[it] ?: server.blocks.firstOrNull { b -> b.blockId == it }?.text.orEmpty()) },
            inspectedBy = draft.inspectedBy.trim(),
            summaryBy = draft.summaryBy.trim(),
        )
    }
}

/** A flagged word with the summary block it was found in. */
data class SpellItem(val blockId: String, val blockLabel: String, val issue: SpellIssue)

/** Pure helpers of the spell-check review (fix / ignore; add to dictionary goes through the server). */
object SpellFlow {
    /** Items still to review: ignored words (case-insensitive) are dropped. */
    fun remaining(items: List<SpellItem>, ignored: Set<String>): List<SpellItem> =
        items.filter { it.issue.word.lowercase() !in ignored }

    /** [text] with the flagged word replaced by [suggestion] (capitalised like the original). Unchanged if the word isn't there. */
    fun applyFix(text: String, issue: SpellIssue, suggestion: String): String {
        val fixed = matchCase(issue.word, suggestion)
        val s = issue.start
        val e = issue.end
        if (s >= 0 && e <= text.length && s < e && text.substring(s, e) == issue.word) {
            return text.substring(0, s) + fixed + text.substring(e)
        }
        val at = Regex("\\b" + Regex.escape(issue.word) + "\\b").find(text) ?: return text
        return text.replaceRange(at.range, fixed)
    }

    private fun matchCase(original: String, suggestion: String): String = when {
        suggestion.isEmpty() -> suggestion
        original.length > 1 && original.all { it.isUpperCase() } -> suggestion.uppercase()
        original.first().isUpperCase() && suggestion.first().isLowerCase() -> suggestion.replaceFirstChar { it.uppercase() }
        else -> suggestion
    }
}

/** Steps of the submit flow (shown as the progress list; a failed step is what "Try again" resumes from). */
enum class SubmitStep(val label: String) {
    PICS("Saving report photos"),
    SPELL("Checking spelling"),
    SUMMARY("Saving summary"),
    BUILD("Building report (PDF and Excel)"),
    PDF("Loading preview"),
}

enum class SubmitErrorKind { GENERIC, FILE_IN_USE, PICS_CHANGED, CHANGED_ELSEWHERE, LOCKED, VALIDATION, UNREACHABLE }

/** A failure the inspector sees: always says that nothing was lost or overwritten. */
data class SubmitError(
    val kind: SubmitErrorKind, val title: String, val message: String, val retryable: Boolean,
    /** For [SubmitErrorKind.CHANGED_ELSEWHERE]: the RMA as the server has it now (null = the screen fetches it). */
    val current: RmaDetail? = null,
)

/** Error mapping for the submit flow (api §6, §7, §9). Messages are for the inspector, not for logs. */
fun submitErrorFor(f: ApiResult.Failure): SubmitError {
    val kept = "Your work is kept on the tablet."
    return when {
        f.code == "REPORT_FILE_IN_USE" -> {
            val name = (f.details?.get("filename") as? JsonPrimitive)?.contentOrNull
            SubmitError(SubmitErrorKind.FILE_IN_USE, "Report file is open",
                "${name ?: "The report file"} is open on someone's PC and can't be replaced. Ask them to close it, then try again. The old report was not changed.", true)
        }
        f.code == "REPORT_PICS_CHANGED" -> SubmitError(SubmitErrorKind.PICS_CHANGED, "Report photos changed",
            "The report photos were changed on the server after you loaded them. Nothing was overwritten. Review the photos in Arrange, then submit again.", false)
        f.code == "RMA_CHANGED" -> SubmitError(SubmitErrorKind.CHANGED_ELSEWHERE, "RMA changed elsewhere",
            "Someone changed this RMA after you loaded it. Nothing was saved. Next you review what changed; $kept", false, conflictCurrent(f.details))
        f.code == "RMA_LOCKED" || f.code == "LOCK_CHECK_FAILED" -> SubmitError(SubmitErrorKind.LOCKED, "RMA is locked",
            f.message + " $kept", true)
        f.code == "VALIDATION_FAILED" || f.httpStatus == 422 -> SubmitError(SubmitErrorKind.VALIDATION, "Can't submit yet",
            validationMessages(f.details).ifEmpty { listOf(f.message) }.joinToString("\n") + "\nNothing was saved. $kept", false)
        f.kind == ApiResult.Kind.UNREACHABLE -> SubmitError(SubmitErrorKind.UNREACHABLE, "Server not reachable",
            f.message + " Nothing was lost. $kept", true)
        else -> SubmitError(SubmitErrorKind.GENERIC, "Couldn't finish the report", f.message + " $kept", f.retryable)
    }
}

private fun validationMessages(details: JsonObject?): List<String> =
    (details?.get("fields") as? JsonArray).orEmpty().mapNotNull {
        runCatching { (it.jsonObject["message"] as? JsonPrimitive)?.contentOrNull }.getOrNull()
    }

/** PDF byte helpers: the preview is only opened for real PDFs, and the page count falls back to the server's number. */
object PdfBytes {
    fun looksLikePdf(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    /** Counts `/Type /Page` objects (not `/Pages`) — a sanity check when the renderer can't tell. */
    fun countPages(bytes: ByteArray): Int =
        PAGE.findAll(String(bytes, Charsets.ISO_8859_1)).count()

    /** The renderer's count wins; else the server's `pages`; else a byte count. Never below 1 for a real PDF. */
    fun resolvePageCount(rendered: Int?, server: Int?, bytes: ByteArray): Int =
        listOfNotNull(rendered, server).firstOrNull { it > 0 } ?: countPages(bytes).coerceAtLeast(1)

    private val PAGE = Regex("/Type\\s*/Page(?![s\\w])")
}
