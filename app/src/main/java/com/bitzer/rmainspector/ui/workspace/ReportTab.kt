package com.bitzer.rmainspector.ui.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitzer.rmainspector.data.CaptionProblem
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.data.PhotoEntry
import com.bitzer.rmainspector.ui.components.InfoBanner
import com.bitzer.rmainspector.ui.components.SearchableDropdown
import com.bitzer.rmainspector.ui.theme.BitzerColors

object ReportTags {
    const val SUMMARY = "report_summary"
    const val ARRANGE = "report_arrange_button"
    const val SAVE_PICS = "report_save_pics"
    const val CONFLICT = "report_conflict"
    const val VIEWER_TOGGLE = "report_viewer_toggle"
}

/**
 * Report tab (spec §7). Landscape: photo viewer + filmstrip on the left, report form on the right. Portrait: the
 * viewer is a collapsible section above the form. With the on-screen keyboard open the viewer collapses so the
 * summary box stays visible. [rows], [selection] and [onSelect] are kept for the shared call site; the Report tab no
 * longer shows the parts list.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportTab(rows: List<FindingRow>, selection: String?, report: ReportState, readOnly: Boolean, twoPane: Boolean, cb: ReportCallbacks,
              onSelect: (String?) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag(WorkspaceTags.listPane(WorkspaceTab.REPORT))) {
        val keyboard = report.keyboardOpen ?: WindowInsets.isImeVisible
        val bodyHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    report.arrange -> ArrangeScreen(report, readOnly, cb)
                    twoPane -> Row(Modifier.fillMaxSize()) {
                        run {
                            Box(Modifier.weight(0.5f).fillMaxHeight()) {
                                ReportPhotoViewerContent(report.viewerState(), cb.viewerCallbacks())
                            }
                            VerticalDivider(color = BitzerColors.Grey30)
                        }
                        Box(Modifier.weight(0.5f).fillMaxHeight().testTag(WorkspaceTags.detailPane(WorkspaceTab.REPORT))) {
                            ReportRight(report, readOnly, cb)
                        }
                    }
                    else -> PortraitReport(report, readOnly, keyboard, bodyHeight, cb)
                }
            }
            if (report.keyboardOpen == true) SimulatedKeyboard()
        }
        report.enlarged?.let { name -> EnlargedPhoto(name, report, readOnly, cb) }
        report.conflict?.let { ConflictDialog(it, report, cb) }
    }
}


/** Stand-in for the soft keyboard in screenshot tests (the real one resizes the window). */
@Composable
private fun SimulatedKeyboard() {
    Box(Modifier.fillMaxWidth().height(260.dp).background(BitzerColors.Grey30), contentAlignment = Alignment.Center) {
        Text("On-screen keyboard (simulated)", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90)
    }
}

/** A photo from the server, or a grey tile with an icon while it loads / when it can't be shown. */
@Composable
private fun PhotoImage(folder: String, filename: String, thumb: Boolean, cb: ReportCallbacks, modifier: Modifier, fit: ContentScale = ContentScale.Crop) {
    val image: ImageBitmap? by produceState<ImageBitmap?>(null, folder, filename, thumb) { value = cb.loadImage(folder, filename, thumb) }
    val img = image
    if (img != null) {
        Image(img, contentDescription = filename, contentScale = fit, modifier = modifier.background(BitzerColors.Grey10, RoundedCornerShape(4.dp)))
    } else {
        Box(modifier.background(BitzerColors.Grey10, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Image, contentDescription = filename, tint = BitzerColors.Grey50, modifier = Modifier.size(36.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------- Right

@Composable
private fun ReportRight(report: ReportState, readOnly: Boolean, cb: ReportCallbacks) {
    val d = report.draft
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReportBanners(report, cb)
        SearchableDropdown(
            label = "General Defect Code",
            items = report.lists.defectCodes,
            selected = d.defectCode,
            onSelect = { cb.onDefect(it) },
            recent = report.lists.recentDefect,
            enabled = !readOnly,
            placeholder = "Tap to choose a defect code",
            onClear = if (d.defectCode != null) ({ cb.onDefect(null) }) else null,
        )
        report.lists.summaryBlocks.forEach { block ->
            val text = d.summary(block.blockId)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(block.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90, modifier = Modifier.weight(1f))
                    if (!readOnly && report.lists.summaryTemplate.isNotBlank()) {
                        OutlinedButton(onClick = { cb.onInsertTemplate(block.blockId) }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text("Insert standard wording")
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { cb.onSummary(block.blockId, it) },
                    enabled = !readOnly,
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth().testTag(ReportTags.SUMMARY),
                    supportingText = {
                        Text("${text.length} / ${block.maxLength}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End,
                            color = if (text.length >= block.maxLength) BitzerColors.Error else BitzerColors.Grey60)
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = d.inspectedBy, onValueChange = cb.onInspectedBy, enabled = !readOnly, singleLine = true,
                label = { Text("Inspected by") }, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
            OutlinedTextField(
                value = d.summaryBy, onValueChange = cb.onSummaryBy, enabled = !readOnly, singleLine = true,
                label = { Text("Summary by") }, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            )
        }
        ReportPhotosCard(report, readOnly, cb)
        Button(
            onClick = cb.onSubmitReport,
            enabled = !readOnly && !report.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ReportSubmitTags.SUBMIT),
            colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
        ) { Text("SUBMIT REPORT") }
    }
}

@Composable
private fun ReportBanners(report: ReportState, cb: ReportCallbacks) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        report.message?.let { m ->
            InfoBanner(if (m.error) Icons.Filled.ErrorOutline else Icons.Filled.Info, m.text, if (m.error) BitzerColors.Error else BitzerColors.DarkGreen) {
                TextButton(onClick = cb.onDismissMessage, modifier = Modifier.heightIn(min = 56.dp)) { Text("OK") }
            }
        }
    }
}

/** "Report photos: n picked, n pages" → Arrange. */
@Composable
private fun ReportPhotosCard(report: ReportState, readOnly: Boolean, cb: ReportCallbacks) {
    val d = report.draft
    Surface(color = BitzerColors.Grey10, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Report photos: ${report.pickedCount} picked, ${report.pageCount} pages", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (d.picksDirty) "Not saved to the server yet" else "Saved on the server",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (d.picksDirty) BitzerColors.Warning else BitzerColors.Grey60,
                )
            }
            Button(
                onClick = { cb.onArrange(true) },
                modifier = Modifier.heightIn(min = 56.dp).testTag(ReportTags.ARRANGE),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
            ) { Text("Arrange") }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Portrait

/** Stacked layout: collapsible photo viewer above the form (the form scrolls; Submit stays reachable above the keyboard). */
@Composable
private fun PortraitReport(report: ReportState, readOnly: Boolean, keyboard: Boolean, bodyHeight: Dp, cb: ReportCallbacks) {
    val expanded = report.partsExpanded && !keyboard
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).background(BitzerColors.Grey10)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide photos" else "Show photos", onClick = cb.onTogglePartsExpanded)
                .testTag(ReportTags.VIEWER_TOGGLE)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PHOTOS", style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = BitzerColors.DarkGreen)
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height((bodyHeight * 0.5f).coerceIn(320.dp, 480.dp))) {
                ReportPhotoViewerContent(report.viewerState(), cb.viewerCallbacks())
            }
            HorizontalDivider(color = BitzerColors.Grey30)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) { ReportRight(report, readOnly, cb) }
    }
}


// ----------------------------------------------------------------------------------------- Enlarged photo

@Composable
private fun EnlargedPhoto(filename: String, report: ReportState, readOnly: Boolean, cb: ReportCallbacks) {
    val picked = report.draft.isPicked(filename)
    Dialog(onDismissRequest = { cb.onEnlarge(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(8.dp), color = BitzerColors.White, modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.9f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(filename.removeSuffix(".jpg"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PhotoImage("all", filename, thumb = false, cb, Modifier.fillMaxWidth().weight(1f), fit = ContentScale.Fit)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!readOnly) {
                        Button(
                            onClick = { cb.onTogglePick(filename) },
                            modifier = Modifier.heightIn(min = 56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (picked) BitzerColors.Grey90 else BitzerColors.Green),
                        ) { Text(if (picked) "Remove from report" else "Add to report") }
                    }
                    OutlinedButton(onClick = { cb.onEnlarge(null) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Conflict

/** `409 REPORT_PICS_CHANGED`: `Report Pics ` changed elsewhere — nothing is overwritten until the inspector chooses (spec §6.5). */
@Composable
private fun ConflictDialog(conflict: ReportPicsConflict, report: ReportState, cb: ReportCallbacks) {
    Dialog(onDismissRequest = cb.onConflictDismiss) { ConflictCard(conflict, report.pickedCount, cb) }
}

/** The dialog's content (also drawn on its own by screenshot tests — a dialog window is a second root there). */
@Composable
fun ConflictCard(conflict: ReportPicsConflict, pickedCount: Int, cb: ReportCallbacks) {
    run {
        Surface(shape = RoundedCornerShape(8.dp), color = BitzerColors.White, modifier = Modifier.testTag(ReportTags.CONFLICT)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.SyncProblem, contentDescription = null, tint = BitzerColors.Warning, modifier = Modifier.size(28.dp))
                    Text("REPORT PHOTOS CHANGED ELSEWHERE", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Someone changed the Report Pics folder after you loaded it. Nothing was saved. " +
                        "The server now has ${conflict.current.size} photo${if (conflict.current.size == 1) "" else "s"}; you have $pickedCount picked.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (conflict.current.isNotEmpty()) {
                    Text("ON THE SERVER", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
                    conflict.current.take(8).forEach { Text(it.removeSuffix(".jpg"), style = MaterialTheme.typography.bodyMedium) }
                    if (conflict.current.size > 8) Text("… and ${conflict.current.size - 8} more", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
                }
                Button(onClick = cb.onConflictUseServer, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green)) { Text("Use the server's list") }
                OutlinedButton(onClick = cb.onConflictKeepMine, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Keep my picks and replace the server's list")
                }
                TextButton(onClick = cb.onConflictDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Decide later") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Arrange

/** Captions and report order for the picked photos. Caption = filename = report order (natural sort, spec §9). */
@Composable
private fun ArrangeScreen(report: ReportState, readOnly: Boolean, cb: ReportCallbacks) {
    val d = report.draft
    val problems = d.captionProblems()
    val positions = d.reportPositions()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { cb.onArrange(false) }, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Report")
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("ARRANGE REPORT PHOTOS", style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen)
                Text("${report.pickedCount} picked, ${report.pageCount} pages · report order = caption order",
                    style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60)
            }
            if (!readOnly) {
                OutlinedButton(onClick = cb.onSortByCaption, enabled = d.picks.size > 1, modifier = Modifier.heightIn(min = 56.dp)) { Text("Sort list") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = cb.onSavePicks, enabled = !report.saving && d.picksDirty,
                    modifier = Modifier.heightIn(min = 56.dp).testTag(ReportTags.SAVE_PICS),
                    colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
                ) { Text(if (report.saving) "Saving…" else "Save report photos") }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(BitzerColors.Green))
        if (report.message != null || d.picksDirty) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ReportBanners(report, cb)
                if (report.message == null && d.picksDirty) {
                    Text("Not saved to the server yet.", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Warning)
                }
            }
        }
        if (d.picks.isEmpty()) {
            Text("No photos picked yet. Long-press a photo on the Photos tab to add it to the report.",
                style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(d.picks, key = { i, p -> "$i|${p.source}|${p.filename}" }) { i, p ->
                    ArrangeRow(i, positions[i], p.source, p.filename, p.caption, problems[i], readOnly, cb)
                }
            }
        }
    }
}

@Composable
private fun ArrangeRow(index: Int, position: Int, source: String, filename: String, caption: String, problem: CaptionProblem?,
                       readOnly: Boolean, cb: ReportCallbacks) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("#$position", style = MaterialTheme.typography.titleMedium, color = BitzerColors.DarkGreen, modifier = Modifier.width(44.dp))
        PhotoImage(if (source == "report_pics") "report" else "all", filename, thumb = true, cb, Modifier.size(width = 112.dp, height = 80.dp))
        Column(Modifier.weight(1f)) {
            OutlinedTextField(
                value = caption, onValueChange = { cb.onCaption(index, it) }, enabled = !readOnly, singleLine = true,
                isError = problem != null, label = { Text("Caption") },
                supportingText = {
                    Text(problem?.text ?: if (source == "report_pics") "Already in Report Pics" else "From $filename", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        }
        if (!readOnly) {
            IconButton(onClick = { cb.onRemovePick(index) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove from the report")
            }
        }
    }
    HorizontalDivider(color = BitzerColors.Grey10)
}