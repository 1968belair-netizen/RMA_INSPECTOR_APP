package com.bitzer.rmainspector.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.ChangeArea
import com.bitzer.rmainspector.data.ChangeLine
import com.bitzer.rmainspector.data.ItemState
import com.bitzer.rmainspector.data.Pick
import com.bitzer.rmainspector.data.SummaryItem
import com.bitzer.rmainspector.ui.components.InfoBanner
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** The layer of the conflict flow; draws nothing while closed. */
@Composable
fun ConflictOverlay(phase: ConflictPhase, cb: ConflictCallbacks, onCloseFailed: () -> Unit) {
    when (phase) {
        ConflictPhase.Closed -> Unit
        ConflictPhase.Loading -> Surface(color = BitzerColors.White, modifier = Modifier.fillMaxSize().testTag(ConflictTags.LOADING)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = BitzerColors.Green)
                Text("Loading the current RMA…", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
            }
        }
        is ConflictPhase.Failed -> SubmitModal(
            tag = ConflictTags.FAILED, title = "Couldn't load the RMA",
            content = { Text(phase.message + "\n\nNothing was changed. Your work is kept on this tablet.", style = MaterialTheme.typography.bodyLarge) },
            buttons = {
                Button(onClick = onCloseFailed, modifier = Modifier.heightIn(min = 56.dp).testTag(ConflictTags.FAILED_CLOSE),
                    colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green)) { Text("OK") }
            },
        )
        is ConflictPhase.Open -> ConflictContent(phase.ui, cb)
    }
}

private fun line(l: ChangeLine): String = when {
    l.before.isEmpty() && l.after.isEmpty() -> l.label
    l.before.isEmpty() -> "${l.label}: ${l.after.shorten()}"
    l.after.isEmpty() -> "${l.label}: ${l.before.shorten()}"
    else -> "${l.label}: ${l.before.shorten().ifEmpty { "(empty)" }}  →  ${l.after.shorten().ifEmpty { "(empty)" }}"
}

private fun String.shorten(max: Int = 140) = replace('\n', ' ').let { if (it.length <= max) it else it.take(max) + "…" }

@Composable
private fun ChangeList(lines: List<ChangeLine>, empty: String, tag: String) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (lines.isEmpty()) Text(empty, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60)
        ChangeArea.values().forEach { area ->
            val inArea = lines.filter { it.area == area }
            if (inArea.isNotEmpty()) {
                Text(area.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                inArea.forEach { Text("•  " + line(it), style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

/**
 * The conflict screen (spec §6.5): what changed on the server next to my edits; no automatic merging. Stateless, so
 * the screenshot tests draw it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConflictContent(ui: ConflictUiState, cb: ConflictCallbacks) {
    val r = ui.review
    Box(Modifier.fillMaxSize()) {
        ScreenFrame(
            ConflictTags.SCREEN, "Changed elsewhere", "RMA ${r.displayNumber}",
            maxWidth = 1100.dp,
            actions = {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = cb.onLater, modifier = Modifier.heightIn(min = 56.dp).testTag(ConflictTags.LATER)) { Text("Decide later") }
                    OutlinedButton(onClick = cb.onSendPhotos, enabled = r.photosPending > 0 && ui.busy == null,
                        modifier = Modifier.heightIn(min = 56.dp).testTag(ConflictTags.PHOTOS)) { Text("Send photos only") }
                    OutlinedButton(onClick = cb.onAskDiscard, enabled = ui.busy == null,
                        modifier = Modifier.heightIn(min = 56.dp).testTag(ConflictTags.DISCARD)) { Text("Discard my changes") }
                    Button(onClick = cb.onKeepMine, enabled = ui.canKeepMine, modifier = Modifier.heightIn(min = 56.dp).testTag(ConflictTags.KEEP),
                        colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green)) { Text("RELOAD AND KEEP MY CHANGES") }
                }
            },
        ) {
            InfoBanner(Icons.Filled.SyncProblem,
                "Someone changed this RMA after you loaded it. Nothing was saved and nothing on the server was overwritten. Review both sides, then choose.",
                BitzerColors.Warning)
            ui.problem?.let { InfoBanner(Icons.Filled.ErrorOutline, it, BitzerColors.Error) }
            ui.message?.let { InfoBanner(Icons.Filled.Info, it, BitzerColors.DarkGreen) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = maxWidth >= 720.dp
                val server: @Composable () -> Unit = {
                    Section("Changed on the server") {
                        ChangeList(r.serverChanges, "No other changes were found.", ConflictTags.SERVER_LIST)
                        if (r.teamOnly || r.serverChanges.any { it.info }) {
                            Text("RMA team fields are for information only — nothing to decide.", style = MaterialTheme.typography.bodyMedium,
                                color = BitzerColors.Grey60, modifier = Modifier.padding(top = 6.dp).testTag(ConflictTags.TEAM_NOTE))
                        }
                    }
                }
                val mine: @Composable () -> Unit = {
                    Section("Your changes") { ChangeList(r.myChanges, "You have no unsent changes to the record.", ConflictTags.MY_LIST) }
                }
                if (side) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { server() }
                    Column(Modifier.weight(1f)) { mine() }
                } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { server(); mine() }
            }
            if (r.reapply.flags.isNotEmpty()) {
                Section("Check these") {
                    r.reapply.flags.forEach { Text("!  $it", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Warning) }
                }
            }
            if (r.bothSides.isNotEmpty()) {
                Section("Changed on both sides — pick one") {
                    r.bothSides.forEach { PickCard(it, ui.picks[it.key], cb) }
                }
            }
            if (r.photosPending > 0) {
                Text("${r.photosPending} photo${if (r.photosPending == 1) "" else "s"} on this tablet ${if (r.photosPending == 1) "is" else "are"} not sent yet. " +
                    "They stay on the tablet unless you choose Send photos only.", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
            }
            if (r.hasMyChanges || r.bothSides.isNotEmpty()) Text(
                "Reload and keep my changes loads the current RMA and puts your changes back on top. Nothing is sent until you submit again from the Review screen.",
                style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
        }
        if (ui.confirmDiscard) SubmitModal(
            tag = "conflict_discard_dialog", title = "Discard your changes?",
            content = { Text("Your unsent parts and summary edits are removed and the RMA is reloaded as it is on the server now. Photos you took stay on this tablet.",
                style = MaterialTheme.typography.bodyLarge) },
            buttons = {
                TextButton(onClick = cb.onCancelDiscard, modifier = Modifier.heightIn(min = 56.dp).testTag(ConflictTags.DISCARD_CANCEL)) { Text("Cancel") }
                Button(onClick = cb.onDiscard, modifier = Modifier.heightIn(min = 56.dp).testTag(ConflictTags.DISCARD_CONFIRM),
                    colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Error)) { Text("Discard") }
            },
        )
        ui.busy?.let { msg ->
            Box(Modifier.fillMaxSize().background(BitzerColors.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(12.dp), color = BitzerColors.White) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = BitzerColors.Green)
                        Text(msg, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** One field changed on both sides: both values side by side and the choice (nothing is chosen for the inspector). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickCard(item: SummaryItem, picked: Pick?, cb: ConflictCallbacks) {
    Surface(shape = RoundedCornerShape(8.dp), color = BitzerColors.Grey10, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val twoCols = maxWidth >= 560.dp
                val mine: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        Text("YOURS", style = MaterialTheme.typography.labelLarge, color = BitzerColors.DarkGreen)
                        Text(item.mine.ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                val server: @Composable (Modifier) -> Unit = { m ->
                    Column(m) {
                        Text("ON THE SERVER", style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
                        Text(item.server.ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (twoCols) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { mine(Modifier.weight(1f)); server(Modifier.weight(1f)) }
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { mine(Modifier.fillMaxWidth()); server(Modifier.fillMaxWidth()) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickButton(item, Pick.MINE, "Keep mine", picked, cb)
                PickButton(item, Pick.SERVER, "Use the server's", picked, cb)
                if (item.isText) PickButton(item, Pick.COMBINE, "Both: server's, then mine", picked, cb)
            }
            if (picked == null) Text("Choose one to continue.", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Warning)
        }
    }
}

@Composable
private fun PickButton(item: SummaryItem, pick: Pick, label: String, picked: Pick?, cb: ConflictCallbacks) {
    val m = Modifier.heightIn(min = 56.dp).testTag(ConflictTags.pick(item.key, pick))
    if (picked == pick) Button(onClick = { cb.onPick(item.key, pick) }, modifier = m,
        colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.DarkGreen)) { Text(label) }
    else OutlinedButton(onClick = { cb.onPick(item.key, pick) }, modifier = m, border = BorderStroke(1.dp, BitzerColors.Grey60)) { Text(label) }
}
