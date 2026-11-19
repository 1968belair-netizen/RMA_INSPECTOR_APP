package com.bitzer.rmainspector.ui.workspace

import com.bitzer.rmainspector.data.SubmitProblem
import com.bitzer.rmainspector.data.SubmitReview

/** What the inspector sees after a submit (spec §6.4). */
data class SubmitDone(
    /** The RMA's status after the submit. */
    val status: String,
    val statusChanged: Boolean,
    val partsSaved: Boolean,
    val photosSent: Int,
    /** Photos the server refused or that were not sent (offline); they stay on the tablet for the next submit. */
    val photoProblems: List<String> = emptyList(),
    /** Photos that can't be filed yet (their part was removed); they stay on the tablet. */
    val photosKept: Int = 0,
    /** null = the report was not rebuilt (not asked); otherwise whether the rebuild worked. */
    val reportRebuilt: Boolean? = null,
    val reportProblem: String? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * The submit could not finish (offline / `503`) and was put in the upload queue (spec §6.4). Nothing is lost.
 * [findingsSaved] = the server already has the findings; only [photosQueued] photos wait.
 */
data class SubmitQueued(
    val findingsSaved: Boolean,
    val photosQueued: Int,
    /** True = the server can't be reached; false = it answered but can't save yet (e.g. Macola lock unconfirmed). */
    val offline: Boolean = true,
) {
    val headline: String get() = if (offline) QUEUED_HEADLINE else "Queued — will retry"
    val detail: String get() = when {
        findingsSaved -> "The findings are saved. $photosQueued photo${if (photosQueued == 1) "" else "s"} will be uploaded " +
            if (offline) "when this tablet is back on the network." else "shortly."
        offline -> "Your findings and photos stay on this tablet and are sent automatically when it is back on the network."
        else -> "The server couldn't confirm it is safe to save yet. Nothing was saved. Your findings and photos stay on this tablet and are sent automatically."
    }

    companion object { const val QUEUED_HEADLINE = "Queued — will send when online" }
}

/** The submit flow (spec §6.1): review → (status warning) → saving / uploading → done, or a problem dialog. */
sealed interface InspectionSubmitPhase {
    data object Closed : InspectionSubmitPhase

    /** Review screen; [warning] = the "Report already complete / sent" dialog is showing on top. */
    data class Review(val review: SubmitReview, val warning: Boolean = false) : InspectionSubmitPhase

    /** [total] = 0 while the findings are being saved; then photos [done] of [total]. */
    data class Working(val step: String, val done: Int = 0, val total: Int = 0) : InspectionSubmitPhase

    data class Done(val done: SubmitDone) : InspectionSubmitPhase

    /** Put in the upload queue; it sends by itself (spec §6.4). */
    data class Queued(val queued: SubmitQueued) : InspectionSubmitPhase

    /** Nothing was lost: the draft and the photos are still on the tablet. */
    data class Failed(val problem: SubmitProblem) : InspectionSubmitPhase
}

/** Callbacks of the submit screens. */
data class SubmitCallbacks(
    /** Submit tapped on the Findings / Photos tab: open the review screen. */
    val onOpen: () -> Unit = {},
    /** Back from the review screen. */
    val onCancel: () -> Unit = {},
    /** SUBMIT on the review screen. */
    val onSubmit: () -> Unit = {},
    /** "Update inspection findings" in the already-complete / already-sent dialog. */
    val onConfirmWarning: () -> Unit = {},
    val onDismissWarning: () -> Unit = {},
    /** Close the problem dialog or the done screen. */
    val onClose: () -> Unit = {},
)

/** Submit is offered on Findings and Photos while something can be edited. */
val WorkspaceState.submitAvailable: Boolean
    get() = (findingsEditable || photosEditable) && tab != WorkspaceTab.REPORT && submit == InspectionSubmitPhase.Closed
