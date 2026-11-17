package com.bitzer.rmainspector.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.core.RmaNumber
import com.bitzer.rmainspector.ui.AppUiState
import com.bitzer.rmainspector.ui.Connection
import com.bitzer.rmainspector.ui.components.BitzerTopBar
import com.bitzer.rmainspector.ui.components.ConnectionBanner
import com.bitzer.rmainspector.ui.components.ScreenHeader
import com.bitzer.rmainspector.ui.theme.BitzerColors

/**
 * Home (docs/spec.md §3.2). This issue adds server status + inspector; search and work list
 * are wired in issue #3.
 */
@Composable
fun HomeScreen(state: AppUiState, onOpenSettings: () -> Unit, onRetry: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val isRma = RmaNumber.isValid(query)

    Scaffold(
        topBar = {
            BitzerTopBar(
                title = "RMA Inspector",
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionBanner(state.connection) {
                    when (state.connection) {
                        is Connection.Error -> Row {
                            TextButton(onClick = onRetry) { Text("Retry") }
                            TextButton(onClick = onOpenSettings) { Text("Settings") }
                        }
                        else -> {}
                    }
                }
                val inspector = state.settings?.inspector
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = BitzerColors.Grey90)
                    Text(
                        if (inspector != null) "  Inspector: ${inspector.displayName} (${inspector.initials})"
                        else "  Inspector: not set — connect to the server first",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            ScreenHeader("Find an RMA", subtitle = "Type an RMA number or a serial number.")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.trim() },
                    label = { Text("RMA no. or serial") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { /* issue #3: GET /rmas/search */ },
                    enabled = query.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Text("  Search")
                }
            }
            if (query.isNotEmpty()) {
                Text(
                    if (isRma) "Looks like an RMA number ($query)." else "Will search by serial number.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitzerColors.Grey90,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            Card(Modifier.fillMaxWidth().padding(24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("WORK LIST", style = MaterialTheme.typography.titleSmall)
                    Text("Coming next (issue #3): RMAs filtered by inspection status.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
