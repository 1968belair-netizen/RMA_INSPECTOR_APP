package com.bitzer.rmainspector.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.SubmitKind
import com.bitzer.rmainspector.data.SubmitReview
import com.bitzer.rmainspector.ui.components.InfoBanner
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** Test tags used by the emulator tests. */
object SubmitTags {
    const val OPEN = "submit_open"
    const val REVIEW = "submit_review"
    const val CONFIRM = "submit_confirm"
    const val CANCEL = "submit_cancel"
    const val STATUS = "submit_status"
    const val WARNING = "submit_warning"
    const val SENT_WARNING = "submit_sent_warning"
    const val WARNING_CONFIRM = "submit_warning_confirm"
    const val WARNING_CANCEL = "submit_warning_cancel"
    const val WORKING = "submit_working"
    const val DONE = "submit_done"
    const val DONE_CLOSE = "submit_done_close"
    const val QUEUED = "submit_queued"
    const val QUEUED_CLOSE = "submit_queued_close"
    const val FAILED = "submit_failed"
    const val FAILED_CLOSE = "submit_failed_close"
}

/** The full-screen layer of the submit flow; draws nothing while [phase] is Closed. */
@Composable
fun SubmitOverlay(phase: InspectionSubmitPhase, cb: SubmitCallbacks) {
    when (phase) {
        InspectionSubmitPhase.Closed -> Unit
        is InspectionSubmitPhase.Review -> {
            Box(Modifier.fillMaxSize()) {
                ReviewScreen(phase.review, cb)
                if (phase.warning) ReportAlreadyDialog(phase.review.plan.kind, phase.review.plan.current?.code.orEmpty(), cb.onConfirmWarning, cb.onDismissWarning)
            }
        }
        is InspectionSubmitPhase.Working -> WorkingScreen(phase)
        is InspectionSubmitPhase.Done -> DoneScreen(phase.done, cb)
        is InspectionSubmitPhase.Queued -> QueuedContent(phase.queued, cb.onClose)
        is InspectionSubmitPhase.Failed -> SubmitProblemDialog(phase.problem, cb.onClose)
    }
}

@Composable
private fun ScreenFrame(tag: String, title: String, subtitle: String?, actions: @Composable RowScope.() -> Unit, body: @Composable () -> Unit) {
    Surface(color = BitzerColors.White, modifier = Modifier.fillMaxSize().testTag(tag)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(BitzerColors.Black).padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = BitzerColors.White)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey30)
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(BitzerColors.Green))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    body()
                }
            }
            HorizontalDivider(color = BitzerColors.Grey30)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen)
        content()
    }
}

@Composable
private fun Bullets(prefix: String, items: List<String>) {
    items.forEach { Text("$prefix  $it", style = MaterialTheme.typography.bodyLarge) }
}

/** What the status will be, in words (spec §6.1). */
internal fun statusLine(review: SubmitReview): String {
    val cur = review.plan.current?.code.orEmpty().ifBlank { "unknown" }
    return when (review.plan.kind) {
        SubmitKind.SET_INSPECTED -> "Status changes from $cur to ${review.plan.resultingStatus}."
        SubmitKind.KEEP -> "Status stays $cur."
        SubmitKind.REPORT_COMPLETE -> "Status stays $cur. The report will be regenerated."
        SubmitKind.REPORT_SENT -> "Status stays $cur. The report will be regenerated and may need to be resent."
    }
}

@Composable
private fun ReviewScreen(r: SubmitReview, cb: SubmitCallbacks) {
    ScreenFrame(
        SubmitTags.REVIEW, "Review submission", "RMA ${r.displayNumber}",
        actions = {
            TextButton(onClick = cb.onCancel, modifier = Modifier.heightIn(min = 56.dp).testTag(SubmitTags.CANCEL)) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = cb.onSubmit, enabled = r.canSubmit, modifier = Modifier.heightIn(min = 56.dp).testTag(SubmitTags.CONFIRM),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
            ) { Text(if (r.plan.rebuildsReport) "UPDATE FINDINGS" else "SUBMIT") }
        },
    ) {
        if (r.incomplete.isNotEmpty()) {
            InfoBanner(Icons.Filled.ErrorOutline, "Choose a part and an issue for these rows before submitting.", BitzerColors.Error)
            Bullets("•", r.incomplete)
        }
        Section("Status") { Text(statusLine(r), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(SubmitTags.STATUS)) }
        Section("Parts") {
            if (!r.partsChanged) Text("No changes to the parts.", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60)
            if (r.added.isNotEmpty()) { Text("Added (${r.added.size})", fontWeight = FontWeight.SemiBold); Bullets("+", r.added) }
            if (r.changed.isNotEmpty()) { Text("Changed (${r.changed.size})", fontWeight = FontWeight.SemiBold); Bullets("~", r.changed) }
            if (r.removed.isNotEmpty()) { Text("Removed (${r.removed.size})", fontWeight = FontWeight.SemiBold); Bullets("−", r.removed) }
            if (r.reordered) Text("The order of the rows changed.", style = MaterialTheme.typography.bodyLarge)
        }
        if (r.summaryChanged) Section("Report summary") { Text("Your summary, defect code and initials edits will be saved.", style = MaterialTheme.typography.bodyLarge) }
        Section("Photos") {
            Text(
                if (r.photosToSend == 0) "No new photos to upload." else "${r.photosToSend} new photo${if (r.photosToSend == 1) "" else "s"} will be uploaded after the findings are saved.",
                style = MaterialTheme.typography.bodyLarge, color = if (r.photosToSend == 0) BitzerColors.Grey60 else BitzerColors.Black,
            )
            if (r.photosSkipped > 0) Text(
                "${r.photosSkipped} photo${if (r.photosSkipped == 1) "" else "s"} can't be filed yet (their part was removed). They stay on this tablet.",
                style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Warning,
            )
        }
        if (!r.canSubmit && r.incomplete.isEmpty()) {
            InfoBanner(Icons.Filled.Info, "Nothing to submit yet. Change a part, add a photo or edit the summary first.", BitzerColors.Grey60)
        }
    }
}

@Composable
private fun WorkingScreen(w: InspectionSubmitPhase.Working) {
    Surface(color = BitzerColors.White, modifier = Modifier.fillMaxSize().testTag(SubmitTags.WORKING)) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(w.step, style = MaterialTheme.typography.headlineSmall)
                if (w.total > 0) {
                    LinearProgressIndicator(progress = { w.done.toFloat() / w.total }, modifier = Modifier.fillMaxWidth(), color = BitzerColors.Green)
                    Text("Photo ${minOf(w.done + 1, w.total)} of ${w.total}", style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90)
                } else {
                    CircularProgressIndicator(color = BitzerColors.Green)
                }
                Text("Keep the app open. Nothing is lost if the connection drops.", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
            }
        }
    }
}

@Composable
private fun DoneScreen(d: SubmitDone, cb: SubmitCallbacks) {
    ScreenFrame(
        SubmitTags.DONE, "Saved", null,
        actions = {
            Spacer(Modifier.weight(1f))
            Button(onClick = cb.onClose, modifier = Modifier.heightIn(min = 56.dp).testTag(SubmitTags.DONE_CLOSE),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green)) { Text("DONE") }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = BitzerColors.Green, modifier = Modifier.padding(end = 4.dp))
            Text(
                if (d.statusChanged) "Saved. Status is now ${d.status}." else "Saved. Status stays ${d.status}.",
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(SubmitTags.STATUS),
            )
        }
        Text(if (d.partsSaved) "Parts saved." else "Parts unchanged.", style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                d.photosSent > 0 -> "${d.photosSent} photo${if (d.photosSent == 1) "" else "s"} uploaded."
                d.photoProblems.isEmpty() -> "No photos to upload."
                else -> "No photos were uploaded."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (d.photoProblems.isNotEmpty()) {
            InfoBanner(Icons.Filled.ErrorOutline,
                "Some photos weren't sent and stay on this tablet. Submit again to retry. ${d.photoProblems.first()}", BitzerColors.Warning)
        }
        if (d.photosKept > 0) Text("${d.photosKept} photo${if (d.photosKept == 1) "" else "s"} can't be filed yet and stay on this tablet.",
            style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Warning)
        when (d.reportRebuilt) {
            true -> Text("The report was regenerated.", style = MaterialTheme.typography.bodyLarge)
            false -> InfoBanner(Icons.Filled.ErrorOutline,
                "The findings are saved, but the report couldn't be regenerated: ${d.reportProblem.orEmpty()} Rebuild it from the Report tab.", BitzerColors.Warning)
            null -> Unit
        }
        d.warnings.forEach { Text("Note: $it", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90) }
    }
}

/** "Queued — will send when online" (spec §6.4). Stateless, so the screenshot tests can draw it. */
@Composable
fun QueuedContent(q: SubmitQueued, onClose: () -> Unit) {
    ScreenFrame(
        SubmitTags.QUEUED, "Queued", null,
        actions = {
            Spacer(Modifier.weight(1f))
            Button(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp).testTag(SubmitTags.QUEUED_CLOSE),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green)) { Text("DONE") }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = BitzerColors.DarkGreen, modifier = Modifier.padding(end = 4.dp))
            Text(q.headline, style = MaterialTheme.typography.headlineSmall)
        }
        Text(q.detail, style = MaterialTheme.typography.bodyLarge)
        Text("Nothing is lost, and nothing is sent under another user's name.", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60)
    }
}
