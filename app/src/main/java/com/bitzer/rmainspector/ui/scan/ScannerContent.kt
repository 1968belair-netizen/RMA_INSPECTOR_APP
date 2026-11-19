package com.bitzer.rmainspector.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.ui.theme.BitzerColors

enum class ScanMode(val label: String) { BARCODE("Barcode / QR"), PLATE("Serial plate text") }

/** What the scanner overlay shows (docs/spec.md §3.1). */
sealed interface ScannerState {
    /** Camera permission not granted yet; [blocked] = the system will no longer ask (open Settings). */
    data class NeedsPermission(val blocked: Boolean = false) : ScannerState
    data object NoCamera : ScannerState
    /** The device has a camera but it could not be opened (busy in another app, disabled by policy, hardware error). */
    data object CameraUnavailable : ScannerState
    /** Live preview; [reading] = a plate photo is being read. */
    data class Scanning(val mode: ScanMode, val reading: Boolean = false) : ScannerState
    /** Plate text recognised: the inspector picks the line to search for. */
    data class PickLine(val lines: List<String>) : ScannerState
    data object NothingRead : ScannerState
    /** Barcode / QR read: shown for a moment, then the search runs. */
    data class Found(val value: String) : ScannerState
}

object ScannerTags {
    const val CLOSE = "scan-close"
    const val CAPTURE = "scan-capture"
    const val GRANT = "scan-grant"
    const val RETRY = "scan-retry"
    fun mode(m: ScanMode) = "scan-mode-${m.name.lowercase()}"
    fun line(i: Int) = "scan-line-$i"
}

/** Stateless scanner overlay: [preview] is the live camera (a dark placeholder in screenshot tests). */
@Composable
fun ScannerContent(
    state: ScannerState,
    onModeChange: (ScanMode) -> Unit,
    onCapture: () -> Unit,
    onPickLine: (String) -> Unit,
    onRetry: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onClose: () -> Unit,
    preview: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(BitzerColors.Black)) {
        val live = state is ScannerState.Scanning || state is ScannerState.Found
        if (live) preview()

        // Viewfinder frame.
        if (state is ScannerState.Scanning || state is ScannerState.Found) {
            val plate = (state as? ScannerState.Scanning)?.mode == ScanMode.PLATE
            Box(
                Modifier.align(Alignment.Center)
                    .fillMaxWidth(if (plate) 0.6f else 0.4f)
                    .heightIn(min = if (plate) 120.dp else 200.dp, max = if (plate) 160.dp else 260.dp)
                    .border(3.dp, if (state is ScannerState.Found) BitzerColors.Green else BitzerColors.White, RoundedCornerShape(12.dp)),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Scan to search", style = MaterialTheme.typography.titleLarge, color = BitzerColors.White,
                modifier = Modifier.weight(1f).padding(start = 16.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(56.dp).testTag(ScannerTags.CLOSE)) {
                Icon(Icons.Filled.Close, contentDescription = "Close scanner", tint = BitzerColors.White)
            }
        }

        when (state) {
            is ScannerState.NeedsPermission -> Message(
                title = if (state.blocked) "Camera access is turned off" else "Camera access needed",
                body = if (state.blocked) "Turn on Camera for RMA Inspector in the tablet's app settings, then come back."
                else "The camera is only used to read a barcode, QR code or serial plate on this tablet. Pictures are never saved or sent.",
                action = if (state.blocked) "Open app settings" else "Allow camera",
                onAction = if (state.blocked) onOpenAppSettings else onGrantPermission,
                actionTag = ScannerTags.GRANT,
            )
            ScannerState.NoCamera -> Message("No camera found", "This device has no usable camera. Type the number instead.", "Close", onClose)
            ScannerState.CameraUnavailable -> Message("The camera couldn't be started",
                "Another app may be using it. Close other camera apps, then try again. You can also type the number instead.", "Try again", onRetry, ScannerTags.RETRY)
            ScannerState.NothingRead -> Message("Nothing readable found", "Hold the plate steady and well lit, then try again.", "Try again", onRetry, ScannerTags.CAPTURE)
            is ScannerState.PickLine -> PickLineSheet(state.lines, onPickLine, onRetry)
            is ScannerState.Scanning -> Bottom {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScanMode.entries.forEach { m ->
                        FilterChip(
                            selected = state.mode == m, onClick = { onModeChange(m) },
                            label = { Text(m.label) },
                            leadingIcon = { Icon(if (m == ScanMode.BARCODE) Icons.Filled.QrCodeScanner else Icons.Filled.TextFields, null) },
                            modifier = Modifier.heightIn(min = 56.dp).testTag(ScannerTags.mode(m)),
                        )
                    }
                }
                if (state.mode == ScanMode.BARCODE) {
                    Text("Point the camera at the barcode or QR code.", color = BitzerColors.White, style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(if (state.reading) "Reading…" else "Frame the serial plate, then tap Capture.",
                        color = BitzerColors.White, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onCapture, enabled = !state.reading, modifier = Modifier.heightIn(min = 56.dp).testTag(ScannerTags.CAPTURE)) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null)
                        Text("  Capture")
                    }
                }
            }
            is ScannerState.Found -> Bottom {
                Text("Found", color = BitzerColors.Green50, style = MaterialTheme.typography.titleMedium)
                Text(state.value, color = BitzerColors.White, style = MaterialTheme.typography.headlineSmall)
                Text("Searching…", color = BitzerColors.White, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Bottom(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(BitzerColors.Black.copy(alpha = 0.6f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Message(
    title: String, body: String, action: String, onAction: () -> Unit, actionTag: String? = null,
) {
    Column(
        Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = BitzerColors.White)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey30)
        Button(onClick = onAction, modifier = Modifier.heightIn(min = 56.dp).let { if (actionTag != null) it.testTag(actionTag) else it }) { Text(action) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PickLineSheet(lines: List<String>, onPick: (String) -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).fillMaxWidth(0.8f).background(BitzerColors.White, RoundedCornerShape(16.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tap the line to search for", style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(lines) { i, line ->
                Text(
                    line, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .background(BitzerColors.Green10, RoundedCornerShape(8.dp))
                        .clickable { onPick(line) }.padding(16.dp).testTag(ScannerTags.line(i)),
                )
            }
        }
        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 56.dp)) { Text("Scan again") }
    }
}
