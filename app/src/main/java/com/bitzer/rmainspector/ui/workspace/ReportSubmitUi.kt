package com.bitzer.rmainspector.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.ReportSubmitRules
import com.bitzer.rmainspector.data.StatusWarning
import com.bitzer.rmainspector.data.SubmitError
import com.bitzer.rmainspector.data.SubmitStep
import com.bitzer.rmainspector.ui.theme.BitzerColors

/**
 * Stateless layer over the workspace for the submit flow (spec §6.2): status dialog, progress, spell-check review,
 * error message and the PDF preview. Drawn as an in-app layer (not a system dialog) so screenshot tests capture it.
 */
@Composable
fun ReportSubmitOverlay(state: ReportSubmitState, cb: ReportSubmitCallbacks) {
    val phase = state.phase
    if (phase == SubmitPhase.Idle) return
    BackHandler(enabled = true) {
        when (phase) {
            is SubmitPhase.Preview -> cb.onDonePreview()
            is SubmitPhase.Error -> cb.onErrorOk()
            else -> cb.onCancel()
        }
    }
    if (phase is SubmitPhase.Preview) {
        PdfPreviewContent(phase.preview, cb.onNeedPage, cb.onDonePreview)
        return
    }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { } }.testTag(ReportSubmitTags.OVERLAY),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp), color = BitzerColors.White, shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
        ) {
            when (phase) {
                is SubmitPhase.Confirm -> ConfirmCard(phase.warning, cb)
                is SubmitPhase.Working -> WorkingCard(phase.step)
                is SubmitPhase.SpellReview -> SpellCard(phase, cb)
                is SubmitPhase.Error -> ErrorCard(phase.error, cb)
                else -> Unit
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.titleLarge, color = BitzerColors.DarkGreen)

@Composable
private fun ConfirmCard(w: StatusWarning, cb: ReportSubmitCallbacks) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CardTitle(if (w == StatusWarning.ALREADY_SENT) "Report already sent" else "Report already complete")
        Text(
            if (w == StatusWarning.ALREADY_SENT) ReportSubmitRules.SENT_WARNING else ReportSubmitRules.COMPLETE_NOTE,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(ReportSubmitTags.CONFIRM),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = cb.onCancel, modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.CANCEL)) { Text("Cancel") }
            Button(
                onClick = cb.onConfirm, modifier = Modifier.heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
            ) { Text("Update report") }
        }
    }
}

@Composable
private fun WorkingCard(current: SubmitStep) {
    Column(Modifier.padding(24.dp).testTag(ReportSubmitTags.WORKING), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardTitle(if (current == SubmitStep.BUILD) "Building the report" else "Submitting the report")
        SubmitStep.entries.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    step.ordinal < current.ordinal -> Text("✓", color = BitzerColors.Green, style = MaterialTheme.typography.titleMedium, modifier = Modifier.size(24.dp))
                    step == current -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = BitzerColors.Green)
                    else -> Box(Modifier.size(24.dp))
                }
                Text(
                    step.label + if (step == current) "…" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (step == current) FontWeight.Bold else FontWeight.Normal,
                    color = if (step.ordinal > current.ordinal) BitzerColors.Grey60 else BitzerColors.Black,
                )
            }
        }
        if (current == SubmitStep.BUILD) Text("This can take up to a minute with many photos.", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpellCard(p: SubmitPhase.SpellReview, cb: ReportSubmitCallbacks) {
    Column(Modifier.padding(24.dp).testTag(ReportSubmitTags.SPELL), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardTitle("Check the spelling")
        if (p.items.isEmpty()) {
            Text("No spelling issues left.", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                "${p.items.size} word${if (p.items.size == 1) "" else "s"} to check. Tap a suggestion to fix it, ignore it, or add it to the shared dictionary.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.items.forEachIndexed { i, item ->
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.issue.word, style = MaterialTheme.typography.titleMedium, color = BitzerColors.Error)
                        Text(item.blockLabel, style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.issue.suggestions.take(4).forEachIndexed { s, suggestion ->
                            Button(
                                onClick = { cb.onFix(i, suggestion) }, enabled = !p.busy,
                                modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.fix(i, s)),
                                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
                            ) { Text("Fix: $suggestion") }
                        }
                        OutlinedButton(onClick = { cb.onIgnore(i) }, enabled = !p.busy,
                            modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.ignore(i))) { Text("Ignore") }
                        OutlinedButton(onClick = { cb.onAddToDictionary(i) }, enabled = !p.busy,
                            modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.dictionary(i))) { Text("Add to dictionary") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (p.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = BitzerColors.Green)
            TextButton(onClick = cb.onCancel, modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.CANCEL)) { Text("Cancel") }
            Button(
                onClick = cb.onSpellContinue, enabled = p.items.isEmpty() && !p.busy,
                modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.SPELL_CONTINUE),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
            ) { Text("Continue") }
        }
    }
}

@Composable
private fun ErrorCard(e: SubmitError, cb: ReportSubmitCallbacks) {
    Column(Modifier.padding(24.dp).testTag(ReportSubmitTags.ERROR), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(e.title.uppercase(), style = MaterialTheme.typography.titleLarge, color = BitzerColors.Error)
        Text(e.message, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if (e.retryable) {
                Button(
                    onClick = cb.onRetry, modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.ERROR_RETRY),
                    colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
                ) { Text("Try again") }
            }
            OutlinedButton(onClick = cb.onErrorOk, modifier = Modifier.heightIn(min = 56.dp).testTag(ReportSubmitTags.ERROR_OK)) { Text("OK") }
        }
    }
}
