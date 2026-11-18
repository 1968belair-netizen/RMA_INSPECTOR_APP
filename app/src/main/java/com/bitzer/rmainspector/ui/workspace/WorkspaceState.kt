package com.bitzer.rmainspector.ui.workspace

import com.bitzer.rmainspector.data.Checklist
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.CodeLists
import com.bitzer.rmainspector.data.DeletedRow
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.data.GuidedProgress
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.data.toFindingRows

/** Workspace tabs (spec §4.2). Filled in milestones B (Findings, Photos) and C (Report). */
enum class WorkspaceTab(val label: String) {
    FINDINGS("Findings"),
    PHOTOS("Photos"),
    REPORT("Report"),
}

/** Loading the RMA itself. */
sealed interface WorkspaceLoad {
    data object Loading : WorkspaceLoad
    data class Loaded(val rma: RmaDetail, val refreshing: Boolean = false) : WorkspaceLoad
    data class Error(val message: String, val notFound: Boolean) : WorkspaceLoad
}

/** The guided inspection button in the header (spec §5). v1 ships with no checklists → [Unavailable]. */
sealed interface GuidedButton {
    data class Unavailable(val productType: String) : GuidedButton
    data class Start(val steps: Int) : GuidedButton
    data class Continue(val step: Int, val of: Int) : GuidedButton
    data object Complete : GuidedButton
}

/** The open guided inspection (D2): current step index, and the "Inspection complete" dialog after the last step. */
data class GuidedRunState(val step: Int = 0, val complete: Boolean = false)

/** Banners under the header (spec §4.1). Shown in the order returned by [workspaceBanners]. */
sealed interface WorkspaceBanner {
    /** Read only: closed, not inspectable, scroll ("Handled in the repair database") or product type not enabled. */
    data class ReadOnly(val reason: ReadOnlyReason, val text: String) : WorkspaceBanner

    /** Open elsewhere in Macola (informational — the real check happens inside the write, api §6). */
    data class Locked(val text: String, val possiblyStale: Boolean) : WorkspaceBanner

    /** The RMA changed on the server since it was loaded (409 RMA_CHANGED). Set by the submit flows (B/C). */
    data object ChangedElsewhere : WorkspaceBanner
}

enum class ReadOnlyReason { CLOSED, NOT_INSPECTABLE, SCROLL, NOT_ENABLED }

/**
 * Everything the stateless [WorkspaceContent] shows.
 * - [selection]: the item picked in the tab's list pane (a part `row_id`, a photo filename …). Landscape shows it in
 *   the right pane; portrait shows the detail instead of the list (back returns to the list).
 * - [changedElsewhere]: slot for the "changed elsewhere" banner; set by the submit flows in milestones B/C.
 */
data class WorkspaceState(
    val load: WorkspaceLoad = WorkspaceLoad.Loading,
    val tab: WorkspaceTab = WorkspaceTab.FINDINGS,
    val selection: String? = null,
    val failureExpanded: Boolean = false,
    val guided: GuidedButton = GuidedButton.Unavailable(""),
    /** The guided inspection screen (D2) while it is open; null = the normal workspace. */
    val guidedRun: GuidedRunState? = null,
    val changedElsewhere: Boolean = false,
    val findings: FindingsState = FindingsState(),
    val photos: PhotosState = PhotosState(),
    /** Report tab data (C1); filled by [ReportViewModel], passed in by [WorkspaceScreen]. */
    val report: ReportState = ReportState(),
) {
    val rma: RmaDetail? get() = (load as? WorkspaceLoad.Loaded)?.rma
    val banners: List<WorkspaceBanner> get() = rma?.let { workspaceBanners(it, changedElsewhere) }.orEmpty()
    val readOnly: Boolean get() = rma?.isReadOnly ?: true

    /** Findings can be edited: not read only and the product type allows findings. */
    val findingsEditable: Boolean get() = !readOnly && rma?.capabilities?.findings == true

    /** Rows shown on the Findings tab: the draft's rows, else the server's rows in server order. */
    val findingRows: List<FindingRow> get() = findings.rows ?: rma?.parts?.toFindingRows().orEmpty()
}

/**
 * Findings tab (spec §4.3).
 * - [rows]: the inspector's rows (draft or freshly loaded); null = not set yet → the server's rows are shown.
 * - [hasDraft]: a local draft exists for this RMA and inspector (saved on every change).
 * - [codes]: code lists on the device (pickers); null until loaded.
 * - [recentParts] / [recentIssues] / [recentCauses]: "recently used" strips of the pickers.
 * - [undo]: the last deleted row, while its Undo is offered.
 */
data class FindingsState(
    val rows: List<FindingRow>? = null,
    val hasDraft: Boolean = false,
    val codes: CodeLists? = null,
    val recentParts: List<CodeKey> = emptyList(),
    val recentIssues: List<CodeKey> = emptyList(),
    val recentCauses: List<CodeKey> = emptyList(),
    val undo: DeletedRow? = null,
) {
    /** Note limit from the server (`limits.note`), 4000 by default. */
    val noteLimit: Int get() = codes?.limits?.get("note") ?: DEFAULT_NOTE_LIMIT

    companion object {
        const val DEFAULT_NOTE_LIMIT = 4000
    }
}

/** No capability at all (closed, not inspectable, scroll, product type not enabled) → the workspace is read only. */
val RmaDetail.isReadOnly: Boolean
    get() {
        val c = capabilities
        return !isOpen || !inspectable || !(c.findings || c.summary || c.photos || c.status || c.report)
    }

const val SCROLL_READ_ONLY_TEXT = "Handled in the repair database. Scroll inspections stay in the Access repair DB — this RMA is read only here."

/** Banners for an RMA, most important first: read only, then lock, then changed elsewhere. */
fun workspaceBanners(rma: RmaDetail, changedElsewhere: Boolean = false): List<WorkspaceBanner> = buildList {
    when {
        !rma.isOpen -> add(WorkspaceBanner.ReadOnly(ReadOnlyReason.CLOSED,
            "This RMA is closed — read only. Ask the RMA team if it needs to be reopened."))
        !rma.inspectable -> add(WorkspaceBanner.ReadOnly(ReadOnlyReason.NOT_INSPECTABLE,
            "This RMA has no inspection record — read only. Ask the RMA team."))
        rma.productTypeKey == "scroll" -> add(WorkspaceBanner.ReadOnly(ReadOnlyReason.SCROLL,
            SCROLL_READ_ONLY_TEXT + if (rma.productTypeSource == "model") " (Product type worked out from the model number.)" else ""))
        rma.isReadOnly -> add(WorkspaceBanner.ReadOnly(ReadOnlyReason.NOT_ENABLED,
            "This RMA can't be inspected in the app (product type ${rma.productType.ifBlank { "unknown" }}) — read only."))
    }
    if (rma.lock.locked) {
        val who = listOfNotNull(rma.lock.heldBy, rma.lock.host?.let { "($it)" }).joinToString(" ").ifBlank { "another user" }
        add(WorkspaceBanner.Locked(
            if (rma.lock.possiblyStale) "Possible stale lock on this RMA. Saving will wait until it's cleared — ask IT."
            else "Open elsewhere by $who right now. You can still prepare the inspection; saving waits until it's closed.",
            rma.lock.possiblyStale,
        ))
    }
    if (changedElsewhere) add(WorkspaceBanner.ChangedElsewhere)
}

/**
 * Guided button state (spec §5) from the server's checklists (keyed by `product_type_key`). Resumable progress
 * (Continue / Complete) comes with the guided flow in milestone B; read-only RMAs never offer it.
 */
fun guidedButtonFor(rma: RmaDetail, checklists: Map<String, Checklist>, progress: GuidedProgress? = null): GuidedButton {
    val list = checklists[rma.productTypeKey]
    if (list == null || list.steps.isEmpty() || rma.isReadOnly) return GuidedButton.Unavailable(rma.productType.ifBlank { "this product type" })
    val n = list.steps.size
    return when {
        progress == null || progress.version != list.version -> GuidedButton.Start(n)
        progress.finished -> GuidedButton.Complete
        else -> GuidedButton.Continue(progress.step.coerceIn(0, n - 1) + 1, n)
    }
}

/** Label for [GuidedButton] — shown on the header button. */
fun GuidedButton.label(): String = when (this) {
    is GuidedButton.Unavailable -> "No guided list for $productType yet"
    is GuidedButton.Start -> "Start guided inspection"
    is GuidedButton.Continue -> "Continue (step $step of $of)"
    GuidedButton.Complete -> "Guided inspection complete"
}
