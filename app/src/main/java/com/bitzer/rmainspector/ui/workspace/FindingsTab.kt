package com.bitzer.rmainspector.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.FindingRow
import com.bitzer.rmainspector.ui.components.SearchableDropdown
import com.bitzer.rmainspector.ui.theme.BitzerColors
import kotlinx.coroutines.delay

/** Test tags of the Findings tab (emulator tests). */
object FindingsTags {
    const val ADD_PART = "findings_add_part"
    const val DRAFT = "findings_draft_saved"
    const val SHEET = "finding_sheet"
    const val NOTE = "finding_note"
    const val MOVE_UP = "finding_move_up"
    const val MOVE_DOWN = "finding_move_down"
    const val DELETE = "finding_delete"
    const val CLOSE = "finding_close"
    const val UNDO = "findings_undo"
    const val ROW_PHOTOS = "finding_row_photos"
    fun row(localId: String) = "finding_row_$localId"
}

/** How long Undo stays offered after a delete. */
private const val UNDO_MILLIS = 8_000L

private fun lineNo(i: Int) = (i + 1).toString().padStart(2, '0')

// ------------------------------------------------------------------------------------------------ List

/**
 * Parts list (spec §4.3): `NN  part · issue · 📷 n · 📝` in the inspector's order, drag handle to reorder, Undo after a
 * delete, and "Add part" always visible at the bottom (hidden only when the RMA is read only).
 */
@Composable
internal fun FindingsList(state: WorkspaceState, cb: WorkspaceCallbacks) {
    val rows = state.findingRows
    val editable = state.findingsEditable
    val f = state.findings
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PARTS", style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen)
            Text("   ${rows.size} row${if (rows.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60,
                modifier = Modifier.weight(1f))
            when {
                !editable -> StatusChip(Icons.Filled.Visibility, "Read only")
                f.hasDraft -> StatusChip(Icons.Filled.Save, "Draft saved on this tablet", Modifier.testTag(FindingsTags.DRAFT))
            }
        }
        HorizontalDivider(color = BitzerColors.Grey30)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No parts recorded yet.", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (editable) "Tap Add part to record the first finding." else "Nothing to show — this RMA is read only.",
                        style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60,
                    )
                }
            } else {
                FindingRows(rows, state.selection, editable, f.photoCounts, cb.onSelect, cb.onMoveRow)
            }
        }
        f.undo?.let { u ->
            LaunchedEffect(u) { delay(UNDO_MILLIS); cb.onDismissUndo() }
            Surface(color = BitzerColors.Grey90, contentColor = BitzerColors.White, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Row ${lineNo(u.index)} deleted" + (u.row.partCode?.let { " (${it.code})" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = cb.onUndoDelete, modifier = Modifier.heightIn(min = 56.dp).testTag(FindingsTags.UNDO)) {
                        Text("UNDO", color = BitzerColors.Green50, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (editable) {
            Button(
                onClick = cb.onAddPart,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp).testTag(FindingsTags.ADD_PART),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("ADD PART")
            }
        }
    }
}

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = BitzerColors.Grey60, modifier = Modifier.size(16.dp))
        Text(" $text", style = MaterialTheme.typography.labelMedium, color = BitzerColors.Grey60)
    }
}

/** The rows, with drag-to-reorder on the handle (each swap happens when the row passes half its neighbour). */
@Composable
private fun FindingRows(
    rows: List<FindingRow>,
    selection: String?,
    editable: Boolean,
    photoCounts: Map<String, Int>,
    onSelect: (String?) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    // Index the dragged row should have after the last move — wait for the list to catch up before moving again.
    val awaiting = remember { arrayOfNulls<Int>(1) }
    val currentRows by rememberUpdatedState(rows)
    val currentOnMove by rememberUpdatedState(onMove)

    fun dragBy(dy: Float) {
        dragDy += dy
        val id = dragId ?: return
        val from = currentRows.indexOfFirst { it.localId == id }
        if (from < 0) return
        val expected = awaiting[0]
        if (expected != null) {
            if (expected != from) return
            awaiting[0] = null
        }
        val items = listState.layoutInfo.visibleItemsInfo
        val me = items.firstOrNull { it.key == id } ?: return
        if (dragDy > 0 && from < currentRows.lastIndex) {
            val next = items.firstOrNull { it.index == me.index + 1 } ?: return
            if (dragDy > next.size / 2f) {
                awaiting[0] = from + 1
                currentOnMove(from, from + 1)
                dragDy -= next.size
            }
        } else if (dragDy < 0 && from > 0) {
            val prev = items.firstOrNull { it.index == me.index - 1 } ?: return
            if (-dragDy > prev.size / 2f) {
                awaiting[0] = from - 1
                currentOnMove(from, from - 1)
                dragDy += prev.size
            }
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(rows, key = { _, r -> r.localId }) { i, row ->
            val dragging = row.localId == dragId
            val itemModifier = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer { translationY = dragDy; shadowElevation = 12f }
            } else {
                Modifier.animateItem()
            }
            FindingListRow(
                index = i, row = row, selected = row.localId == selection, photos = photoCounts[row.localId] ?: 0,
                editable = editable, last = i == rows.lastIndex,
                onClick = { onSelect(row.localId) },
                onMove = onMove,
                handle = if (!editable) null else Modifier.pointerInput(row.localId) {
                    detectDragGestures(
                        onDragStart = { dragId = row.localId; dragDy = 0f; awaiting[0] = null },
                        onDragEnd = { dragId = null; dragDy = 0f },
                        onDragCancel = { dragId = null; dragDy = 0f },
                        onDrag = { change, amount -> change.consume(); dragBy(amount.y) },
                    )
                },
                modifier = itemModifier,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindingListRow(
    index: Int,
    row: FindingRow,
    selected: Boolean,
    photos: Int,
    editable: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onMove: (Int, Int) -> Unit,
    handle: Modifier?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(if (selected) BitzerColors.Green10 else BitzerColors.White)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable(onClick = onClick)
                .semantics {
                    if (editable) {
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move up") { onMove(index, index - 1); true })
                            if (!last) add(CustomAccessibilityAction("Move down") { onMove(index, index + 1); true })
                        }
                    }
                }
                .testTag(FindingsTags.row(row.localId))
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lineNo(index), style = MaterialTheme.typography.titleMedium, color = BitzerColors.Grey60, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CodeText(row.partCode, "Part not chosen")
                    Text("·", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60)
                    CodeText(row.issueCode, "Issue not chosen")
                    row.causeCode?.let {
                        Text("·", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60)
                        CodeText(it, "")
                    }
                }
                if (row.note.isNotBlank()) {
                    Text(row.note.replace('\n', ' '), style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.isNew) {
                Text("NEW", style = MaterialTheme.typography.labelSmall, color = BitzerColors.DarkGreen,
                    modifier = Modifier.border(1.dp, BitzerColors.Green, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(8.dp))
            }
            if (photos > 0) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "$photos photos", tint = BitzerColors.Grey90, modifier = Modifier.size(18.dp))
                Text(" $photos", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
                Spacer(Modifier.width(8.dp))
            }
            if (row.note.isNotBlank()) {
                Icon(Icons.Filled.EditNote, contentDescription = "Has a note", tint = BitzerColors.Grey90, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(4.dp))
            }
            if (handle != null) {
                Box(
                    handle.size(56.dp).semantics { contentDescription = "Drag to reorder" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.DragHandle, contentDescription = null, tint = BitzerColors.Grey60) }
            }
        }
        HorizontalDivider(color = BitzerColors.Grey10)
    }
}

@Composable
private fun CodeText(code: Code?, missing: String) {
    if (code == null) {
        Text(missing, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Warning)
    } else {
        Row {
            Text(code.code, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = BitzerColors.DarkGreen)
            if (code.description.isNotBlank()) Text("  ${code.description}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// ----------------------------------------------------------------------------------------------- Sheet

/**
 * Part detail (spec §4.3) as a sheet over Findings: part, issue (`50` Normal first), cause (only when the server
 * sends causes), note (≤ limit, counter) and the row's photos. Every change is saved to the draft at once.
 */
@Composable
internal fun FindingSheet(state: WorkspaceState, cb: WorkspaceCallbacks) {
    val rows = state.findingRows
    val index = rows.indexOfFirst { it.localId == state.selection }
    val editable = state.findingsEditable
    if (index < 0) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Select a part to see it here.", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90)
            Spacer(Modifier.size(8.dp))
            Text(if (editable) "Or tap Add part to record a new finding." else "This RMA is read only.",
                style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
        }
        return
    }
    val row = rows[index]
    val f = state.findings
    val codes = f.codes
    val id = row.localId
    Surface(
        color = BitzerColors.White,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, BitzerColors.Grey30),
        modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp).testTag(FindingsTags.SHEET),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PART ${lineNo(index)}", style = MaterialTheme.typography.headlineSmall, color = BitzerColors.Black)
                    Text(
                        when {
                            !editable -> "Read only"
                            row.isNew -> "New row — saved on this tablet until you submit"
                            else -> "Row from the RMA — changes are saved on this tablet until you submit"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60,
                    )
                }
                TextButton(onClick = { cb.onSelect(null) }, modifier = Modifier.heightIn(min = 56.dp).testTag(FindingsTags.CLOSE)) { Text("Close") }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(BitzerColors.Green))
            if (codes == null && editable) {
                Text("The code lists aren't on this tablet yet — connect to the server to pick codes.",
                    style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Warning)
            }
            SearchableDropdown(
                label = "Part", items = codes?.parts.orEmpty(), selected = row.partCode, onSelect = { cb.onSetPart(id, it) },
                hidden = codes?.hidden?.parts.orEmpty(), recent = f.recentParts, enabled = editable,
            )
            SearchableDropdown(
                label = "Issue", items = codes?.issues.orEmpty(), selected = row.issueCode, onSelect = { cb.onSetIssue(id, it) },
                pinned = codes?.normalIssueCodes.orEmpty(), hidden = codes?.hidden?.issues.orEmpty(), recent = f.recentIssues,
                enabled = editable,
            )
            if (codes?.causesEnabled == true || row.causeCode != null) {
                SearchableDropdown(
                    label = "Cause", items = codes?.causes.orEmpty(), selected = row.causeCode, onSelect = { cb.onSetCause(id, it) },
                    hidden = codes?.hidden?.causes.orEmpty(), recent = f.recentCauses, enabled = editable,
                    placeholder = "Optional", onClear = { cb.onSetCause(id, null) },
                )
            }
            val limit = f.noteLimit
            Column {
                Text("NOTE", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
                Spacer(Modifier.size(4.dp))
                OutlinedTextField(
                    value = row.note,
                    onValueChange = { cb.onSetNote(id, it.take(limit)) },
                    readOnly = !editable,
                    minLines = 3,
                    placeholder = { Text(if (editable) "Optional note" else "No note") },
                    supportingText = {
                        Text("${row.note.length} / $limit", modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            color = if (row.note.length >= limit) BitzerColors.Warning else BitzerColors.Grey60)
                    },
                    modifier = Modifier.fillMaxWidth().testTag(FindingsTags.NOTE),
                )
            }
            RowPhotosSlot(row, f.photoCounts[id] ?: 0, editable)
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { cb.onMoveRow(index, index - 1) }, enabled = index > 0,
                        modifier = Modifier.heightIn(min = 56.dp).testTag(FindingsTags.MOVE_UP)) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = null); Text(" Move up")
                    }
                    OutlinedButton(onClick = { cb.onMoveRow(index, index + 1) }, enabled = index < rows.lastIndex,
                        modifier = Modifier.heightIn(min = 56.dp).testTag(FindingsTags.MOVE_DOWN)) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = null); Text(" Move down")
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { cb.onDeleteRow(id) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BitzerColors.Error),
                        border = BorderStroke(1.dp, BitzerColors.Error),
                        modifier = Modifier.heightIn(min = 56.dp).testTag(FindingsTags.DELETE),
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null); Text(" Delete row")
                    }
                }
            }
        }
    }
}

/**
 * Photos of this row — the slot B2 (#7) fills (take / retake / delete, filename preview, keyed by
 * [FindingRow.localId]). Until then it only shows the count the state carries.
 */
@Composable
private fun RowPhotosSlot(@Suppress("UNUSED_PARAMETER") row: FindingRow, count: Int, editable: Boolean) {
    Column(Modifier.testTag(FindingsTags.ROW_PHOTOS)) {
        Text("PHOTOS FOR THIS ROW", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
        Spacer(Modifier.size(4.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 88.dp)
                .border(1.dp, BitzerColors.Grey30, RoundedCornerShape(4.dp))
                .background(BitzerColors.Grey10)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = BitzerColors.Grey60, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    count > 0 -> "$count photo${if (count == 1) "" else "s"}"
                    editable -> "Row photos come with the camera step (next update)."
                    else -> "No photos for this row."
                },
                style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60,
            )
        }
    }
}
