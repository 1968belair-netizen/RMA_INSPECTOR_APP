package com.bitzer.rmainspector.ui.workspace

import androidx.compose.ui.graphics.ImageBitmap
import com.bitzer.rmainspector.data.ReportDraft
import com.bitzer.rmainspector.data.SpellItem
import com.bitzer.rmainspector.data.StatusWarning
import com.bitzer.rmainspector.data.SubmitError
import com.bitzer.rmainspector.data.SubmitStep

object ReportSubmitTags {
    const val SUBMIT = "report_submit_button"
    const val OVERLAY = "submit_overlay"
    const val CONFIRM = "submit_confirm"
    const val CANCEL = "submit_cancel"
    const val WORKING = "submit_working"
    const val SPELL = "submit_spell"
    const val SPELL_CONTINUE = "submit_spell_continue"
    const val ERROR = "submit_error"
    const val ERROR_OK = "submit_error_ok"
    const val ERROR_RETRY = "submit_error_retry"
    const val PREVIEW = "submit_preview"
    const val PAGE_COUNTER = "submit_preview_counter"
    const val DONE = "submit_preview_done"
    fun fix(item: Int, suggestion: Int) = "submit_fix_${item}_$suggestion"
    fun ignore(item: Int) = "submit_ignore_$item"
    fun dictionary(item: Int) = "submit_dictionary_$item"
}

/** What the preview shows after a build. */
data class PdfPreviewState(
    val title: String = "",
    val pageCount: Int = 0,
    val pages: Map<Int, ImageBitmap> = emptyMap(),
    /** Short notes under the title: status set, warnings from the build. */
    val notes: List<String> = emptyList(),
)

sealed interface SubmitPhase {
    data object Idle : SubmitPhase
    /** Report already complete / sent: ask before regenerating. */
    data class Confirm(
        val warning: StatusWarning,
        /** Server name of the Report Complete status, for the dialog text. */
        val completeStatus: String = com.bitzer.rmainspector.data.ReportSubmitRules.DEFAULT_REPORT_COMPLETE,
    ) : SubmitPhase
    data class Working(val step: SubmitStep) : SubmitPhase
    data class SpellReview(val items: List<SpellItem>, val busy: Boolean = false) : SubmitPhase
    data class Preview(val preview: PdfPreviewState) : SubmitPhase
    data class Error(val error: SubmitError) : SubmitPhase
}

data class ReportSubmitState(val phase: SubmitPhase = SubmitPhase.Idle)

/** Submit-flow actions; defaults do nothing so screenshots can pass `ReportSubmitCallbacks()`. */
data class ReportSubmitCallbacks(
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onFix: (item: Int, suggestion: String) -> Unit = { _, _ -> },
    val onIgnore: (item: Int) -> Unit = {},
    val onAddToDictionary: (item: Int) -> Unit = {},
    val onSpellContinue: () -> Unit = {},
    val onErrorOk: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onNeedPage: (Int) -> Unit = {},
    val onDonePreview: () -> Unit = {},
)

/** Things the submit flow tells the workspace (draft changed, reload the RMA / photo list). */
sealed interface SubmitEvent {
    data class DraftChanged(val draft: ReportDraft) : SubmitEvent
    data object ReloadRma : SubmitEvent

    /** `409 RMA_CHANGED` while saving the summary: open the conflict screen ([base] = the RMA as loaded). */
    data class Conflict(val base: com.bitzer.rmainspector.data.RmaDetail, val current: com.bitzer.rmainspector.data.RmaDetail?) : SubmitEvent
    data object ReloadPhotos : SubmitEvent
}
