package com.bitzer.rmainspector.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.SubmitKind
import com.bitzer.rmainspector.data.SubmitProblem
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** Warning text when a report was already sent (spec §6.1). */
const val REPORT_SENT_WARNING = "This report was already sent. Updating will regenerate it; you may need to resend it."

/**
 * A card on a dimmed background, drawn inside the screen (not a separate window), so screenshot tests show it.
 * Tapping outside does nothing — every choice is a button.
 */
@Composable
internal fun SubmitModal(tag: String, title: String, content: @Composable () -> Unit, buttons: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(BitzerColors.Black.copy(alpha = 0.45f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp), color = BitzerColors.White, shadowElevation = 8.dp,
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).testTag(tag),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = BitzerColors.Black)
                content()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    buttons()
                }
            }
        }
    }
}

/**
 * "Report already complete" (current status Report Complete or later) and its already-sent variant (spec §6.1):
 * "Update inspection findings" saves, keeps the status and regenerates the report.
 */
@Composable
fun ReportAlreadyDialog(kind: SubmitKind, status: String, onUpdate: () -> Unit, onCancel: () -> Unit) {
    SubmitModal(
        tag = SubmitTags.WARNING,
        title = "Report already complete",
        content = {
            Text("This RMA is at \"$status\". Updating saves your findings and keeps that status. The report will be regenerated.",
                style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90)
            if (kind == SubmitKind.REPORT_SENT) {
                Text(REPORT_SENT_WARNING, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Warning,
                    modifier = Modifier.testTag(SubmitTags.SENT_WARNING))
            }
        },
        buttons = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp).testTag(SubmitTags.WARNING_CANCEL)) { Text("Cancel") }
            Button(
                onClick = onUpdate, modifier = Modifier.heightIn(min = 56.dp).testTag(SubmitTags.WARNING_CONFIRM),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green),
            ) { Text("Update inspection findings") }
        },
    )
}

/** Something stopped the submit; nothing was lost. Shows the server's own message, never overwrites anything. */
@Composable
fun SubmitProblemDialog(problem: SubmitProblem, onClose: () -> Unit) {
    SubmitModal(
        tag = SubmitTags.FAILED,
        title = problem.title,
        content = { Text(problem.message, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey90) },
        buttons = {
            Button(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp).testTag(SubmitTags.FAILED_CLOSE),
                colors = ButtonDefaults.buttonColors(containerColor = BitzerColors.Green)) { Text("OK") }
        },
    )
}
