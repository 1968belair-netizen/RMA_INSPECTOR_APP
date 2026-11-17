package com.bitzer.rmainspector.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.BuildConfig
import com.bitzer.rmainspector.ui.Connection
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** One-line server status used on Home and Settings. */
@Composable
fun ConnectionBanner(connection: Connection, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    val (icon, color, text) = when (connection) {
        Connection.Unknown, Connection.Checking -> Triple(Icons.Filled.Sync, BitzerColors.Grey60, "Checking the server…")
        is Connection.Ok -> if (connection.updateRequired) {
            Triple(Icons.Filled.ErrorOutline, BitzerColors.Warning,
                "Update required: this app is ${BuildConfig.VERSION_NAME}, the server needs ${connection.health.minAppVersion} or newer. Install the latest build.")
        } else {
            Triple(Icons.Filled.CheckCircle, BitzerColors.Green,
                "Server connected" + if (connection.health.backend == "mock") " (mock server — fake data)" else "")
        }
        is Connection.Error -> Triple(Icons.Filled.ErrorOutline, BitzerColors.Error, connection.message)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = BitzerColors.Grey10),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) { Text(text, style = MaterialTheme.typography.bodyLarge) }
            action()
        }
    }
}
