package com.bitzer.rmainspector.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.BuildConfig
import com.bitzer.rmainspector.ui.AppUiState
import com.bitzer.rmainspector.ui.Connection
import com.bitzer.rmainspector.ui.components.BitzerTopBar
import com.bitzer.rmainspector.ui.components.ConnectionBanner
import com.bitzer.rmainspector.ui.components.InspectorList
import com.bitzer.rmainspector.ui.components.ScreenHeader
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** Settings (docs/spec.md §3.14): server address, inspector, app info. */
@Composable
fun SettingsScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSaveServer: (String) -> String?,
    onResetServer: () -> Unit,
    onTest: () -> Unit,
    onSelectInspector: (com.bitzer.rmainspector.data.Inspector) -> Unit,
) {
    val saved = state.settings?.serverUrl.orEmpty()
    var text by rememberSaveable { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saved) { if (!edited) text = saved }

    Scaffold(
        topBar = {
            BitzerTopBar(
                title = "Settings",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ScreenHeader(
                "Server",
                subtitle = "Address of the RMA app server. For the mock server on a PC, use the PC's address and port 8000, " +
                    "e.g. 192.168.1.20:8000 — this device must be on the same Wi-Fi.",
            )
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; edited = true; error = null },
                    label = { Text("Server address") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "Saved: $saved") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { error = onSaveServer(text); if (error == null) edited = false }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { error = onSaveServer(text); if (error == null) edited = false },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text("Save & test") }
                    OutlinedButton(onClick = onTest, modifier = Modifier.heightIn(min = 56.dp)) { Text("Test again") }
                    TextButton(
                        onClick = { edited = false; error = null; onResetServer() },
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text("Use default") }
                }
                ConnectionBanner(state.connection)
            }

            ScreenHeader("Inspector", subtitle = "Who is doing the inspections on this device. Used for the audit trail and your initials.")
            Column(Modifier.padding(horizontal = 24.dp)) {
                when {
                    state.inspectors.isNotEmpty() ->
                        InspectorList(state.inspectors, state.settings?.inspector?.username, onSelectInspector)
                    state.connection is Connection.Ok ->
                        Text("The server has no inspectors set up.", style = MaterialTheme.typography.bodyLarge)
                    else ->
                        Text(
                            state.settings?.inspector?.let { "${it.displayName} (${it.initials}) — connect to the server to change." }
                                ?: "Connect to the server to load the inspector list.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                }
            }

            ScreenHeader("App info")
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                (state.connection as? Connection.Ok)?.health?.let {
                    Text("Server ${it.serverVersion} · API ${it.apiVersion}", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90)
                }
            }
        }
    }
}
