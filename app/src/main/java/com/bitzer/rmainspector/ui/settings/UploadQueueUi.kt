package com.bitzer.rmainspector.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.QueueGroup
import com.bitzer.rmainspector.data.QueueGroups
import com.bitzer.rmainspector.data.QueueItem
import com.bitzer.rmainspector.data.QueueItemRules
import com.bitzer.rmainspector.data.QueueState
import com.bitzer.rmainspector.data.SignOutPrompt
import com.bitzer.rmainspector.data.SignOutRules
import com.bitzer.rmainspector.ui.components.ScreenHeader
import com.bitzer.rmainspector.ui.theme.BitzerColors
import com.bitzer.rmainspector.ui.workspace.SubmitModal

object QueueTags {
    const val SECTION = "queue-section"
    const val EMPTY = "queue-empty"
    const val SIGN_OUT_PROMPT = "signout-prompt"
    const val SEND_NOW = "signout-send-now"
    const val SIGN_OUT_ANYWAY = "signout-anyway"
    const val STAY = "signout-stay"
    const val CANCEL_CONFIRM = "queue-cancel-confirm"
    const val CANCEL_KEEP = "queue-cancel-keep"
    fun group(username: String) = "queue-group-$username"
    fun item(id: String) = "queue-item-$id"
    fun retry(id: String) = "queue-retry-$id"
    fun cancel(id: String) = "queue-cancel-$id"
}

/**
 * Settings → Upload queue (spec §6.6): items grouped by inspector. The signed-in inspector's own items can be retried,
 * cancelled (after a confirmation) and opened for the error detail; another inspector's items are read-only — only
 * their count and RMA numbers are shown, with no way to open, send, retry or cancel them.
 */
@Composable
fun UploadQueueSection(
    items: List<QueueItem>,
    username: String?,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    var cancelling by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenHeader(
        "Upload queue",
        subtitle = "Uploads that could not be sent yet. They stay on this tablet and are sent while their inspector is signed in.",
    )
    Column(Modifier.padding(horizontal = 24.dp).testTag(QueueTags.SECTION), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val groups = QueueGroups.build(items, username)
        if (groups.isEmpty()) {
            Text("Nothing is waiting to be sent.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(QueueTags.EMPTY))
        }
        groups.forEach { g ->
            Column(Modifier.testTag(QueueTags.group(g.inspector.username)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(QueueGroups.heading(g), style = MaterialTheme.typography.titleMedium)
                Text(QueueGroups.summary(g), style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90)
                g.items.forEach { item ->
                    if (g.mine) OwnItem(item, username, now, onRetry, onAskCancel = { cancelling = it }) else OtherItem(item)
                }
            }
        }
    }
    cancelling?.let { id ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text(QueueItemRules.CANCEL_TITLE) },
            text = { Text(QueueItemRules.CANCEL_BODY) },
            confirmButton = {
                Button(onClick = { cancelling = null; onCancel(id) }, modifier = Modifier.heightIn(min = 56.dp).testTag(QueueTags.CANCEL_CONFIRM)) {
                    Text("Cancel upload")
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelling = null }, modifier = Modifier.heightIn(min = 56.dp).testTag(QueueTags.CANCEL_KEEP)) {
                    Text("Keep it")
                }
            },
        )
    }
}

@Composable
private fun OwnItem(item: QueueItem, username: String?, now: Long, onRetry: (String) -> Unit, onAskCancel: (String) -> Unit) {
    var detail by rememberSaveable(item.id) { mutableStateOf(false) }
    val tint = if (item.isBlocked) BitzerColors.Error else BitzerColors.Black
    Card(Modifier.fillMaxWidth().testTag(QueueTags.item(item.id)), colors = CardDefaults.cardColors(containerColor = BitzerColors.Grey10)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("RMA ${item.displayNumber}", style = MaterialTheme.typography.titleMedium)
                if (item.state == QueueState.SENDING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(QueueItemRules.status(item), style = MaterialTheme.typography.bodyLarge, color = tint)
            if (item.photosLeft > 0) {
                Text(if (item.photosLeft == 1) "1 photo to send" else "${item.photosLeft} photos to send",
                    style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (QueueItemRules.canRetry(item, username)) {
                    OutlinedButton(onClick = { onRetry(item.id) }, modifier = Modifier.heightIn(min = 56.dp).testTag(QueueTags.retry(item.id))) {
                        Text("Retry now")
                    }
                }
                if (QueueItemRules.canCancel(item, username)) {
                    TextButton(onClick = { onAskCancel(item.id) }, modifier = Modifier.heightIn(min = 56.dp).testTag(QueueTags.cancel(item.id))) {
                        Text("Cancel", color = BitzerColors.Error)
                    }
                }
                TextButton(onClick = { detail = !detail }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(if (detail) "Hide details" else "Details")
                }
            }
            if (detail) {
                Text(QueueItemRules.detail(item, now).ifBlank { "No problem reported." }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Read only: another inspector's item shows the RMA and nothing else (no state, no error text, no buttons). */
@Composable
private fun OtherItem(item: QueueItem) {
    Card(Modifier.fillMaxWidth().testTag(QueueTags.item(item.id)), colors = CardDefaults.cardColors(containerColor = BitzerColors.Grey10)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = BitzerColors.Grey60, modifier = Modifier.size(20.dp))
            Text("RMA ${item.displayNumber}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The sign-out prompt (spec §2), drawn on the screen like the submit dialogs. */
@Composable
fun SignOutPromptModal(
    prompt: SignOutPrompt,
    onSendNow: () -> Unit,
    onSignOutAnyway: () -> Unit,
    onStay: () -> Unit,
) {
    val sending = prompt is SignOutPrompt.Sending
    SubmitModal(
        tag = QueueTags.SIGN_OUT_PROMPT,
        title = SignOutRules.title(prompt),
        content = {
            Text(SignOutRules.body(prompt), style = MaterialTheme.typography.bodyLarge)
            if (sending) CircularProgressIndicator(Modifier.size(32.dp))
            // Stacked full-width buttons: three side by side would not fit a phone.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSendNow, enabled = !sending, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(QueueTags.SEND_NOW)) {
                    Text(if (prompt is SignOutPrompt.Offline || prompt is SignOutPrompt.Problem) "Try again" else "Send now")
                }
                OutlinedButton(onClick = onSignOutAnyway, enabled = !sending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(QueueTags.SIGN_OUT_ANYWAY)) { Text("Sign out anyway") }
                TextButton(onClick = onStay, enabled = !sending, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(QueueTags.STAY)) {
                    Text("Stay signed in")
                }
            }
        },
        buttons = {},
    )
}
