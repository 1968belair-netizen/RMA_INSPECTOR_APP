package com.bitzer.rmainspector.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.Inspector

/** Inspector rows with 56 dp touch targets. Used in Settings and the "Who's inspecting?" dialog. */
@Composable
fun InspectorList(inspectors: List<Inspector>, selected: String?, onSelect: (Inspector) -> Unit) {
    Column {
        inspectors.forEach { i ->
            val isSel = i.username.equals(selected, ignoreCase = true)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(i) })
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSel, onClick = null)
                Text(
                    "  ${i.displayName}" + if (i.initials.isNotBlank()) "  (${i.initials})" else "",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** spec §3.1 — shown when the server lists more than one inspector and none is chosen. No password in v1. */
@Composable
fun InspectorPickDialog(inspectors: List<Inspector>, onSelect: (Inspector) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("WHO'S INSPECTING?") },
        text = { InspectorList(inspectors, selected = null, onSelect = onSelect) },
        confirmButton = {},
    )
}
