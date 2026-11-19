package com.bitzer.rmainspector.ui.workspace

import androidx.compose.ui.graphics.ImageBitmap
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.data.PhotoEntry
import com.bitzer.rmainspector.data.ReportDraft
import com.bitzer.rmainspector.data.ReportSettings
import com.bitzer.rmainspector.data.SummaryBlockDef

/** Photo list of the Report tab's strip (`GET /photos`). */
sealed interface ReportPhotosLoad {
    data object Loading : ReportPhotosLoad
    data object Loaded : ReportPhotosLoad
    data class Error(val message: String) : ReportPhotosLoad
}

/** `409 REPORT_PICS_CHANGED`: `Report Pics ` was changed elsewhere (api §9.2). Always shown for review (spec §6.5). */
data class ReportPicsConflict(val current: List<String>, val currentSignature: String)

/** Code lists and settings the Report tab needs (from the cached `/codelists`). */
data class ReportLists(
    val defectCodes: List<Code> = emptyList(),
    val recentDefect: List<CodeKey> = emptyList(),
    val summaryBlocks: List<SummaryBlockDef> = listOf(SummaryBlockDef("1", "Report summary", 4000)),
    val summaryTemplate: String = "",
    val settings: ReportSettings = ReportSettings(),
)

/** A short message under the tab banners: [error] = needs attention. */
data class ReportMessage(val text: String, val error: Boolean = false)

/**
 * Everything the stateless Report tab shows (spec §7). [draft] holds the editable data (defect code, summary,
 * by-fields, picks); the rest is screen state.
 * - [showAll]: photo strip ignores the selected part. [enlarged]: filename shown full size.
 * - [arrange]: Arrange screen (captions, order) instead of the two panes.
 * - [partsExpanded]: portrait only — the collapsible parts/photos section above the form.
 * - [keyboardOpen]: null = detect the real on-screen keyboard; true/false forces it (screenshot tests).
 */
data class ReportState(
    val draft: ReportDraft = ReportDraft(),
    val photosLoad: ReportPhotosLoad = ReportPhotosLoad.Loading,
    val photos: List<PhotoEntry> = emptyList(),
    val lists: ReportLists = ReportLists(),
    val showAll: Boolean = false,
    val enlarged: String? = null,
    val arrange: Boolean = false,
    val partsExpanded: Boolean = true,
    val saving: Boolean = false,
    val message: ReportMessage? = null,
    val conflict: ReportPicsConflict? = null,
    val keyboardOpen: Boolean? = null,
    /** Photo viewer (#82): "Report photos" set instead of all photos (remembered per RMA) and the photo it shows. */
    val viewerReportOnly: Boolean = false,
    val viewerSelected: String? = null,
) {
    val pickedCount: Int get() = draft.picks.size
    val pageCount: Int get() = ReportDraft.pageCount(pickedCount, lists.settings)
}

/** Report tab actions; defaults do nothing so previews and screenshots can pass `ReportCallbacks()`. */
data class ReportCallbacks(
    val onToggleShowAll: () -> Unit = {},
    val onEnlarge: (String?) -> Unit = {},
    val onTogglePick: (String) -> Unit = {},
    val onDefect: (Code?) -> Unit = {},
    val onSummary: (blockId: String, text: String) -> Unit = { _, _ -> },
    val onInsertTemplate: (blockId: String) -> Unit = {},
    val onInspectedBy: (String) -> Unit = {},
    val onSummaryBy: (String) -> Unit = {},
    val onArrange: (Boolean) -> Unit = {},
    val onCaption: (index: Int, text: String) -> Unit = { _, _ -> },
    val onRemovePick: (index: Int) -> Unit = {},
    val onSortByCaption: () -> Unit = {},
    val onSavePicks: () -> Unit = {},
    val onConflictUseServer: () -> Unit = {},
    val onConflictKeepMine: () -> Unit = {},
    val onConflictDismiss: () -> Unit = {},
    val onTogglePartsExpanded: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
    val onRetryPhotos: () -> Unit = {},
    /** Photo viewer (#82): the shown photo changed / the All-Report toggle was set. */
    val onViewerSelect: (key: String) -> Unit = {},
    val onViewerReportOnly: (Boolean) -> Unit = {},
    /** "Submit report" (C2): spell check, build, preview. */
    val onSubmitReport: () -> Unit = {},
    /** Loads a photo as an image (`folder` = `all` / `report`); null = not available (placeholder tile). */
    val loadImage: suspend (folder: String, filename: String, thumb: Boolean) -> ImageBitmap? = { _, _, _ -> null },
)

/**
 * Photos shown in the strip for the selected part (spec §7): by the saved row link (`row_id`), else by row position
 * (`seq` = row position at upload), else by part code when the server doesn't know the position. [row] null =
 * nothing selected → all photos. [position] is the 1-based position of [row] in the parts list.
 */
fun photosForPart(photos: List<PhotoEntry>, row: FindingRow?, position: Int): List<PhotoEntry> {
    if (row == null) return photos
    return photos.filter { p ->
        (p.rowId != null && (p.rowId == row.rowId || p.rowId == row.localId)) ||
            (p.seq != null && p.seq == position) ||
            (p.seq == null && p.partCode != null && p.partCode == row.partCode?.key)
    }
}
