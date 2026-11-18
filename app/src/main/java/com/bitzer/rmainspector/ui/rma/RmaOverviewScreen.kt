package com.bitzer.rmainspector.ui.rma

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.ui.components.BitzerTopBar
import com.bitzer.rmainspector.ui.components.InfoBanner
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** RMA overview (docs/spec.md §3.4): read-only header, failure context, findings so far, lock and read-only banners. */
@Composable
fun RmaOverviewScreen(onBack: () -> Unit, vm: RmaOverviewViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    RmaOverviewContent(state = state, onBack = onBack, onRefresh = vm::load)
}

/** Stateless overview content — used by [RmaOverviewScreen] and by screenshot tests. */
@Composable
fun RmaOverviewContent(state: OverviewState, onBack: () -> Unit, onRefresh: () -> Unit) {
    val title = (state as? OverviewState.Loaded)?.rma?.displayNumber?.let { "RMA $it" } ?: "RMA"

    Scaffold(
        topBar = {
            BitzerTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                OverviewState.Loading -> Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("  Loading the RMA…", style = MaterialTheme.typography.bodyLarge)
                }
                is OverviewState.Error -> Column(Modifier.padding(24.dp)) {
                    InfoBanner(Icons.Filled.ErrorOutline, s.message, BitzerColors.Error) {
                        if (!s.notFound) TextButton(onClick = onRefresh) { Text("Retry") }
                    }
                }
                is OverviewState.Loaded -> {
                    if (s.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Overview(s.rma)
                }
            }
        }
    }
}

@Composable
private fun Overview(rma: RmaDetail) {
    val h = rma.header
    val caps = rma.capabilities
    val canInspect = caps.findings || caps.summary || caps.photos || caps.status || caps.report
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- Read-only / lock banners (spec §3.4, §7)
        when {
            !rma.isOpen -> InfoBanner(Icons.Filled.Block, "This RMA is closed — read only. Ask the RMA team if it needs to be reopened.", BitzerColors.Grey60)
            !rma.inspectable -> InfoBanner(Icons.Filled.Block, "This RMA has no inspection record — read only. Ask the RMA team.", BitzerColors.Grey60)
            rma.productTypeKey == "scroll" -> InfoBanner(Icons.Filled.Info,
                "Scroll inspections are recorded in the Access repair DB. This RMA is read only here." +
                    if (rma.productTypeSource == "model") " (Product type worked out from the model number.)" else "",
                BitzerColors.Grey60)
            !canInspect -> InfoBanner(Icons.Filled.Block, "This RMA can't be inspected in the app (product type ${rma.productType.ifBlank { "unknown" }}).", BitzerColors.Grey60)
        }
        if (rma.lock.locked) {
            val who = listOfNotNull(rma.lock.heldBy, rma.lock.host?.let { "($it)" }).joinToString(" ").ifBlank { "another user" }
            InfoBanner(
                Icons.Filled.Lock,
                if (rma.lock.possiblyStale) "Possible stale lock on this RMA. Saving will wait until it's cleared — ask IT."
                else "Open elsewhere by $who right now. You can still prepare the inspection; saving waits until it's closed.",
                BitzerColors.Warning,
            )
        }

        Section("RMA") {
            Field("Customer", h.customerName)
            Field("Model", h.model)
            Field("Serial", h.failedSerialNo)
            Field("Sales order", h.salesOrderNo)
            Field("Product type", rma.productType.ifBlank { "—" } + if (rma.productTypeSource == "model") "  (from model: ${rma.productTypeKey})" else "")
            Field("Return code", h.returnCode.label())
            Field("Inspection status", h.inspectionStatus.label())
            Field("Credit status", h.creditStatus.ifBlank { "Not set yet" })
            Field("Failed date", h.failedDate)
            Field("RMA comment", h.rmaComment)
        }

        Section("Failure context", note = "Read only — RMA team fields") {
            Field("Failure code", h.failureCode)
            Field("Failure description", h.failureDescription)
            Field("Comments", h.comments)
        }

        Section("Parts recorded", note = "${rma.parts.size} row${if (rma.parts.size == 1) "" else "s"}") {
            if (rma.parts.isEmpty()) Text("No parts recorded yet.", style = MaterialTheme.typography.bodyLarge)
            rma.parts.forEachIndexed { i, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text((i + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.bodyLarge,
                        color = BitzerColors.Grey60, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${row.partCode.label()}  ·  ${row.issueCode.label()}", style = MaterialTheme.typography.bodyLarge)
                        row.causeCode?.let { Text("Cause: ${it.label()}", style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90) }
                        if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey90)
                    }
                }
                if (i < rma.parts.lastIndex) HorizontalDivider(color = BitzerColors.Grey30)
            }
        }

        Section("Summary") {
            Field("General defect code", rma.summary.defectCode.label().ifBlank { "Not set" })
            val blocks = rma.summary.blocks
            blocks.forEach { b ->
                Field(if (blocks.size == 1) "Report summary" else "Summary ${b.blockId}", b.text.ifBlank { "Empty" })
            }
            Field("Inspected by", rma.summary.inspectedBy)
            Field("Summary by", rma.summary.summaryBy)
        }

        Section("Photos") {
            Text(
                if (rma.photos.folderFound) "${rma.photos.count} photo${if (rma.photos.count == 1) "" else "s"} in ${rma.photos.folderDisplay}"
                else "No photo folder yet — it's created with the first photo.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (canInspect) {
            Button(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 240.dp)) {
                Text("Start inspection (coming in the next issues)")
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

private fun Code?.label(): String = this?.let { if (it.description.isBlank()) it.code else "${it.code}  ${it.description}" } ?: ""

@Composable
private fun Section(title: String, note: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = BitzerColors.White), border = CardDefaults.outlinedCardBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, color = BitzerColors.DarkGreen)
                if (note != null) Text("   $note", style = MaterialTheme.typography.bodySmall, color = BitzerColors.Grey60)
            }
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BitzerColors.Grey60, modifier = Modifier.width(180.dp))
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
    }
}
