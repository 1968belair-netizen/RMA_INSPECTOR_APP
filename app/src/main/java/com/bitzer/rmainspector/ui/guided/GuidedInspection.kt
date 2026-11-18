package com.bitzer.rmainspector.ui.guided

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.PhotoItem
import com.bitzer.rmainspector.data.PhotoSource
import com.bitzer.rmainspector.data.SlotStatus
import com.bitzer.rmainspector.data.StepStatus
import com.bitzer.rmainspector.ui.components.SearchableDropdown
import com.bitzer.rmainspector.ui.theme.BitzerColors
import com.bitzer.rmainspector.ui.workspace.PhotoThumb
import com.bitzer.rmainspector.ui.workspace.WorkspaceState
import com.bitzer.rmainspector.ui.workspace.photosEditable

/** Test tags for the guided inspection screen. */
object GuidedTags {
    const val SCREEN = "guided_screen"
    const val EXIT = "guided_exit"
    const val NOTE = "guided_note"
    const val BACK = "guided_back"
    const val SKIP = "guided_skip"
    const val NEXT = "guided_next"
    const val DONE = "guided_done"
    const val COMPLETE = "guided_complete"
    fun slot(slot: String) = "guided_slot_$slot"
    fun take(slot: String) = "guided_take_$slot"
}

/** Callbacks of the guided screen (spec §5). Photos are addressed by photo id, slots by the checklist's `slot` name. */
data class GuidedCallbacks(
    val onIssue: (Code) -> Unit = {},
    val onNote: (String) -> Unit = {},
    val onTakePhoto: (slot: String) -> Unit = {},
    val onRetake: (photoId: String) -> Unit = {},
    val onDeletePhoto: (photoId: String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** Leave the guided screen (progress is kept); also closes the "Inspection complete" dialog. */
    val onExit: () -> Unit = {},
)

/** Full-screen layer over the workspace while a guided run is open; nothing when it is not. */
@Composable
fun GuidedOverlay(state: WorkspaceState, cb: GuidedCallbacks) {
    val run = state.guidedRun ?: return
    val status = state.guidedStatus()
    BackHandler(onBack = cb.onExit)
    Surface(Modifier.fillMaxSize().testTag(GuidedTags.SCREEN), color = BitzerColors.White) {
        if (status == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("This guided list isn't available any more.", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = cb.onExit, modifier = Modifier.heightIn(min = 56.dp)) { Text("Back to the workspace") }
            }
        } else {
            GuidedStep(state, status, cb)
        }
    }
    if (run.complete) CompleteCard(status?.total ?: 0, cb.onExit)
}

@Composable
private fun GuidedStep(state: WorkspaceState, st: StepStatus, cb: GuidedCallbacks) {
    val rma = state.rma
    val editable = state.findingsEditable
    val photosEditable = state.photosEditable
    val codes = state.findings.codes
    val part = state.guidedPart(st)
    Column(Modifier.fillMaxSize()) {
        // Header: exit, title, step counter, progress.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cb.onExit, modifier = Modifier.heightIn(min = 56.dp).testTag(GuidedTags.EXIT)) {
                Icon(Icons.Filled.Close, contentDescription = null); Text("  Exit")
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("GUIDED INSPECTION", style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen)
                Text("RMA ${rma?.displayNumber.orEmpty()} · ${rma?.productType.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
            }
            Text("Step ${st.index + 1} of ${st.total}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp))
        }
        LinearProgressIndicator(progress = { (st.index + 1f) / st.total }, modifier = Modifier.fillMaxWidth(),
            color = BitzerColors.Green, trackColor = BitzerColors.Grey10)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(part.code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = BitzerColors.DarkGreen)
                    Text("   " + part.description, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                SearchableDropdown(
                    label = "Issue", items = codes?.issues.orEmpty(), selected = st.issue, onSelect = cb.onIssue,
                    pinned = codes?.normalIssueCodes.orEmpty(), hidden = codes?.hidden?.issues.orEmpty(),
                    recent = state.findings.recentIssues, enabled = editable,
                )
                Text(
                    if (st.isNormal) "Normal is pre-selected. Change it only if you find something."
                    else "Something was found: the detail photo below is required.",
                    style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60,
                )
                val limit = state.findings.noteLimit
                val note = st.row?.note.orEmpty()
                Column {
                    Text("NOTE", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
                    Spacer(Modifier.size(4.dp))
                    OutlinedTextField(
                        value = note, onValueChange = { cb.onNote(it.take(limit)) }, readOnly = !editable, minLines = 2,
                        placeholder = { Text("Optional note") },
                        supportingText = {
                            Text("${note.length} / $limit", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End, color = BitzerColors.Grey60)
                        },
                        modifier = Modifier.fillMaxWidth().testTag(GuidedTags.NOTE),
                    )
                }
                if (st.slots.isNotEmpty()) {
                    Text("PHOTOS", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
                    st.slots.forEach { SlotCard(it, photosEditable, cb) }
                }
            }
        }
        HorizontalDivider(color = BitzerColors.Grey30)
        BottomBar(st, cb)
    }
}

@Composable
private fun SlotCard(slot: SlotStatus, editable: Boolean, cb: GuidedCallbacks) {
    val missingRequired = slot.required && !slot.done
    Row(
        Modifier.fillMaxWidth().border(1.dp, if (missingRequired) BitzerColors.Warning else BitzerColors.Grey30, RoundedCornerShape(8.dp))
            .padding(12.dp).testTag(GuidedTags.slot(slot.slot)),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val photo = slot.photo
        val tile = Modifier.size(width = 132.dp, height = 99.dp)
        if (photo != null) {
            PhotoThumb(PhotoItem(photo.photoId, PhotoSource.Local(photo.photoId, photo.file), null, isPreview = true, local = photo), tile)
        } else {
            Box(tile.background(BitzerColors.Grey10, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Icon(if (slot.coveredByExisting) Icons.Filled.Check else Icons.Filled.PhotoCamera, contentDescription = null, tint = BitzerColors.Grey60)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(slot.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(10.dp))
                Badge(if (slot.required) "REQUIRED" else "OPTIONAL", if (slot.required) BitzerColors.Warning else BitzerColors.Grey60)
            }
            Text(
                when {
                    photo != null -> "Photo taken (saved on this tablet)"
                    slot.coveredByExisting -> "A photo of this part is already on the RMA"
                    slot.required -> "Required: take this photo to continue"
                    else -> "Not taken"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (missingRequired) BitzerColors.Warning else BitzerColors.Grey90,
            )
        }
        if (photo != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { cb.onRetake(photo.photoId) }, enabled = editable, modifier = Modifier.heightIn(min = 56.dp)) { Text("Retake") }
                TextButton(onClick = { cb.onDeletePhoto(photo.photoId) }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            }
        } else {
            Button(onClick = { cb.onTakePhoto(slot.slot) }, enabled = editable, modifier = Modifier.heightIn(min = 56.dp).testTag(GuidedTags.take(slot.slot))) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null); Text("  Take photo")
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = BitzerColors.White,
        modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun BottomBar(st: StepStatus, cb: GuidedCallbacks) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!st.canNext) {
            val n = st.missingRequired
            Text(
                if (n > 0) "$n required photo${if (n == 1) "" else "s"} missing. Skip and Next unlock when ${if (n == 1) "it is" else "they are"} taken."
                else "Choose an issue to continue.",
                style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Warning,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = cb.onBack, enabled = st.index > 0, modifier = Modifier.heightIn(min = 56.dp).testTag(GuidedTags.BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null); Text("  Back")
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = cb.onSkip, enabled = st.canSkip, modifier = Modifier.heightIn(min = 56.dp).testTag(GuidedTags.SKIP)) { Text("Skip") }
            Button(onClick = cb.onNext, enabled = st.canNext, modifier = Modifier.heightIn(min = 56.dp).testTag(GuidedTags.NEXT)) {
                Text(if (st.isLast) "Finish  " else "Next  ")
                Icon(if (st.isLast) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

/** "Inspection complete" (spec §5): drawn in the screen (not a platform dialog) so screenshot tests can capture it. */
@Composable
private fun CompleteCard(total: Int, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize().background(BitzerColors.Black.copy(alpha = 0.5f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = BitzerColors.White, shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 480.dp).padding(24.dp).testTag(GuidedTags.COMPLETE)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("INSPECTION COMPLETE", style = MaterialTheme.typography.titleLarge, color = BitzerColors.DarkGreen)
                Text("All $total steps are done. The findings and photos are saved on this tablet. Review them on the Findings and Photos tabs, then submit the inspection.",
                    style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(GuidedTags.DONE)) { Text("Back to the workspace") }
            }
        }
    }
}
