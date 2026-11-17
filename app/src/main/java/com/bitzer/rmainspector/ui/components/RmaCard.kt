package com.bitzer.rmainspector.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.RmaListItem
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** One RMA in the work list or search results. Shows display_number; rma_id is only used to open it. */
@Composable
fun RmaCard(item: RmaListItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val readOnly = !item.isOpen || !item.inspectable
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (readOnly) BitzerColors.Grey10 else BitzerColors.White),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RMA ${item.displayNumber}", style = MaterialTheme.typography.titleMedium)
                    if (item.locked) {
                        Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = BitzerColors.Warning,
                            modifier = Modifier.padding(start = 8.dp).size(20.dp))
                    }
                }
                Text(
                    listOf(item.model, item.productType.ifBlank { "No product type" }, item.failedSerialNo.ifBlank { null })
                        .filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitzerColors.Grey90,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val status = item.inspectionStatus
                Text(status?.let { it.description.ifBlank { it.code } } ?: "",
                    style = MaterialTheme.typography.labelLarge, color = BitzerColors.DarkGreen)
                when {
                    !item.isOpen -> Text("Closed", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60)
                    !item.inspectable -> Text("Not inspectable", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60)
                    item.productTypeKey == "scroll" -> Text("Scroll — read only", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = BitzerColors.Grey60)
        }
    }
}
