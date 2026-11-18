package com.bitzer.rmainspector.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.RmaAge
import com.bitzer.rmainspector.data.RmaListItem
import com.bitzer.rmainspector.ui.theme.BitzerColors
import java.time.LocalDate

/** Read only on the tablet: closed, not inspectable, or scroll (handled in the repair database). */
val RmaListItem.readOnly: Boolean get() = !isOpen || !inspectable || productTypeKey == "scroll"

/**
 * One RMA in the work list or search results (spec §3.2): RMA, model, product type, return code, age, status
 * and badges. Shows display_number; rma_id is only used to open it. [today] is passed in so tests are stable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RmaCard(item: RmaListItem, today: LocalDate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (item.readOnly) BitzerColors.Grey10 else BitzerColors.White),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("RMA ${item.displayNumber}", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    item.flags.forEach { flag ->
                        val text = if (flag.code == "CORE_TEARDOWN") "Core teardown" else flag.label.ifBlank { flag.code }
                        Badge(text, Icons.Filled.Build, BitzerColors.DarkGreen, BitzerColors.Green10)
                    }
                    if (item.locked) Badge("Open elsewhere", Icons.Filled.Lock, BitzerColors.Warning, Color(0xFFFFF1DC))
                    if (item.readOnly) Badge("Read only", Icons.Filled.Visibility, BitzerColors.Grey90, BitzerColors.White)
                }
                Text(
                    listOf(
                        item.model,
                        item.productType.ifBlank { "No product type" },
                        item.returnCode?.let { it.description.ifBlank { it.code } } ?: "No return code",
                    ).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (item.failedSerialNo.isNotBlank()) {
                    Text("S/N ${item.failedSerialNo}", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val status = item.inspectionStatus
                Text(status?.let { it.description.ifBlank { it.code } } ?: "",
                    style = MaterialTheme.typography.labelLarge, color = BitzerColors.DarkGreen)
                RmaAge.label(item.statusSince, item.statusSinceEstimated, today)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = if (item.statusSinceEstimated) BitzerColors.Grey60 else BitzerColors.Grey90)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = BitzerColors.Grey60)
        }
    }
}

@Composable
private fun Badge(text: String, icon: ImageVector, color: Color, background: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = background,
        contentColor = color,
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(" $text", style = MaterialTheme.typography.labelMedium)
        }
    }
}
