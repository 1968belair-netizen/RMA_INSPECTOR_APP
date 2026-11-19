package com.bitzer.rmainspector.ui.workspace

import com.bitzer.rmainspector.data.ConflictReview
import com.bitzer.rmainspector.data.Pick

/** Test tags of the conflict screen (emulator tests). */
object ConflictTags {
    const val SCREEN = "conflict_screen"
    const val SERVER_LIST = "conflict_server_list"
    const val MY_LIST = "conflict_my_list"
    const val TEAM_NOTE = "conflict_team_note"
    const val KEEP = "conflict_keep"
    const val DISCARD = "conflict_discard"
    const val DISCARD_CONFIRM = "conflict_discard_confirm"
    const val DISCARD_CANCEL = "conflict_discard_cancel"
    const val PHOTOS = "conflict_photos"
    const val LATER = "conflict_later"
    const val LOADING = "conflict_loading"
    const val FAILED = "conflict_failed"
    const val FAILED_CLOSE = "conflict_failed_close"
    fun pick(key: String, pick: Pick) = "conflict_pick_${key}_${pick.name.lowercase()}"
}

/**
 * What the conflict screen shows: the [review], the inspector's [picks] for fields changed on both sides, and screen
 * state. [message] = result of "Send photos only"; [problem] = something went wrong (nothing was lost).
 */
data class ConflictUiState(
    val review: ConflictReview,
    val picks: Map<String, Pick> = emptyMap(),
    val busy: String? = null,
    val confirmDiscard: Boolean = false,
    val message: String? = null,
    val problem: String? = null,
) {
    val canKeepMine: Boolean get() = busy == null && review.canKeepMine(picks)
}

/** The conflict flow around the workspace (spec §6.5). */
sealed interface ConflictPhase {
    data object Closed : ConflictPhase
    data object Loading : ConflictPhase
    data class Failed(val message: String) : ConflictPhase
    data class Open(val ui: ConflictUiState) : ConflictPhase
}

/** Conflict screen actions; defaults do nothing so screenshots can pass `ConflictCallbacks()`. */
data class ConflictCallbacks(
    val onPick: (key: String, pick: Pick) -> Unit = { _, _ -> },
    val onKeepMine: () -> Unit = {},
    val onAskDiscard: () -> Unit = {},
    val onCancelDiscard: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onSendPhotos: () -> Unit = {},
    val onLater: () -> Unit = {},
)

/** What the conflict flow tells the workspace after the inspector chose. */
sealed interface ConflictOutcome {
    /** The draft was rewritten or removed: reload the RMA; [openReview] = go straight back to the Review screen. */
    data class Resolved(val openReview: Boolean) : ConflictOutcome
}
