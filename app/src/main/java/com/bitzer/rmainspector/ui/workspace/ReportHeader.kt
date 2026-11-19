package com.bitzer.rmainspector.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.ui.theme.BitzerColors

object ReportHeaderTags {
    const val LINE = "report_header_line"
    const val DETAILS = "report_header_details"
    const val MORE = "report_header_more"
    const val PANEL = "report_header_panel"
    const val STATUS = "report_header_status"
    fun field(f: HeaderField) = "report_header_field_${f.name.lowercase()}"
}

/** Report-tab header data (issue #81). [expanded]: the Details panel is open. */
data class ReportHeaderState(
    val rmaNumber: String,
    val customer: String,
    val model: String,
    val productType: String,
    val serial: String,
    val returnCode: String,
    val status: String,
    val failureCode: String,
    val failureDescription: String,
    val comments: String,
    val creditStatus: String,
    val failedDate: String,
    val rmaComment: String,
    val guided: GuidedButton = GuidedButton.Unavailable(""),
    val expanded: Boolean = false,
) {
    /** Slim-line failure text: "9-Oil Issue — low oil pressure trips" (parts that exist). */
    val failureLine: String get() = listOf(failureCode, failureDescription).filter { it.isNotBlank() }.joinToString(" — ")

    fun value(f: HeaderField): String = when (f) {
        HeaderField.CUSTOMER -> customer
        HeaderField.MODEL -> model
        HeaderField.SERIAL -> serial
        HeaderField.RETURN_CODE -> returnCode
        HeaderField.FAILURE -> failureLine
    }.ifBlank { "—" }
}

data class ReportHeaderCallbacks(
    val onToggleDetails: () -> Unit = {},
    val onGuided: () -> Unit = {},
)

fun reportHeaderState(rma: RmaDetail, expanded: Boolean, guided: GuidedButton): ReportHeaderState {
    val h = rma.header
    return ReportHeaderState(
        rmaNumber = rma.displayNumber, customer = h.customerName, model = h.model,
        productType = rma.productType.ifBlank { "Unknown" } + if (rma.productTypeSource == "model") " (from model)" else "",
        serial = h.failedSerialNo, returnCode = headerCodeText(h.returnCode), status = headerCodeText(h.inspectionStatus),
        failureCode = h.failureCode, failureDescription = h.failureDescription, comments = h.comments,
        creditStatus = h.creditStatus.ifBlank { "Not set yet" }, failedDate = h.failedDate, rmaComment = h.rmaComment,
        guided = guided, expanded = expanded,
    )
}

/** "50  Normal"; a code whose description repeats it (return codes, statuses) shows once. */
private fun headerCodeText(c: Code?): String = when {
    c == null -> ""
    c.description.isBlank() || c.description == c.code -> c.code
    else -> c.label
}

private fun fieldLabel(f: HeaderField): String = when (f) {
    HeaderField.CUSTOMER -> "Customer"
    HeaderField.MODEL -> "Model"
    HeaderField.SERIAL -> "Serial"
    HeaderField.RETURN_CODE -> "Return code"
    HeaderField.FAILURE -> "Failure"
}

private val LINE_GAP = 16.dp
private val LABEL_GAP = 6.dp
private val FAILURE_MAX = 280.dp
private val CHIP_PAD = 12.dp
private val CONTROL_PAD = 8.dp
/** Windows narrower than this (split-screen, portrait + large font) use the two-line header before dropping anything. */
val REPORT_HEADER_TWO_LINE_BELOW = 640.dp
private val SLACK = 8.dp

/**
 * Slim Report-tab header (issue #81): one line with RMA, customer, model, serial, return code, failure info and the
 * status chip, plus a **Details** control and a "+n" chip for fields that did not fit. Fonts never shrink; fields
 * move into Details in a fixed order ([fitReportHeader]). Stateless: [state] in, [callbacks] out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportHeaderContent(state: ReportHeaderState, callbacks: ReportHeaderCallbacks, modifier: Modifier = Modifier) {
    Surface(color = BitzerColors.White, modifier = modifier.fillMaxWidth().testTag(WorkspaceTags.HEADER)) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val type = MaterialTheme.typography
                val rmaStyle = type.titleLarge
                val labelStyle = type.labelSmall
                val valueStyle = type.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                val statusStyle = type.titleSmall
                val detailsStyle = type.titleSmall
                fun px(d: Dp) = with(density) { d.roundToPx() }
                fun textPx(s: String, st: TextStyle) = measurer.measure(s, st, maxLines = 1, softWrap = false).size.width

                val rmaText = "RMA ${state.rmaNumber}"
                val rmaPx = textPx(rmaText, rmaStyle)
                val statusText = state.status.ifBlank { "—" }
                val statusPx = textPx(statusText, statusStyle) + 2 * px(CHIP_PAD)
                val detailsPx = textPx("DETAILS", detailsStyle) + px(4.dp) + px(24.dp) + 2 * px(CONTROL_PAD)
                val moreText = "+${HeaderField.DISPLAY_ORDER.size}"
                val chipPx = maxOf(textPx(moreText, statusStyle) + 2 * px(CHIP_PAD), px(48.dp))

                fun fieldPx(f: HeaderField, value: String): Int {
                    val natural = textPx(fieldLabel(f).uppercase(), labelStyle) + px(LABEL_GAP) + textPx(value, valueStyle)
                    return if (f == HeaderField.FAILURE) minOf(natural, px(FAILURE_MAX)) else natural
                }
                val widths = HeaderField.DISPLAY_ORDER.map { f ->
                    val v = state.value(f)
                    FieldWidth(
                        f, fieldPx(f, v),
                        if (f == HeaderField.CUSTOMER) fieldPx(f, ellipsizeCustomer(v)) else null,
                    )
                }
                val fit = fitReportHeader(
                    FitParams(
                        availablePx = constraints.maxWidth - px(SLACK), gapPx = px(LINE_GAP), rmaPx = rmaPx, statusPx = statusPx,
                        detailsPx = detailsPx, chipPx = chipPx, twoLineBelowPx = px(REPORT_HEADER_TWO_LINE_BELOW),
                    ),
                    widths,
                )

                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ReportHeaderTags.LINE), verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(LINE_GAP), verticalAlignment = Alignment.CenterVertically) {
                            Text(rmaText, style = rmaStyle, color = BitzerColors.Black, maxLines = 1, softWrap = false)
                            if (!state.expanded && !fit.twoLine) {
                                fit.visible.forEach { f ->
                                    SlimField(f, if (f == HeaderField.CUSTOMER && fit.customerShort) ellipsizeCustomer(state.value(f)) else state.value(f),
                                        labelStyle, valueStyle)
                                }
                            }
                            StatusChip(statusText, statusStyle)
                        }
                        Spacer(Modifier.weight(1f).widthIn(min = LINE_GAP))
                        if (!state.expanded && fit.hidden > 0) MoreChip("+${fit.hidden}", statusStyle, callbacks.onToggleDetails)
                        DetailsControl(state.expanded, detailsStyle, callbacks.onToggleDetails)
                    }
                    if (!state.expanded && fit.twoLine) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(LINE_GAP),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            fit.visible.forEach { f ->
                                SlimField(f, if (f == HeaderField.CUSTOMER && fit.customerShort) ellipsizeCustomer(state.value(f)) else state.value(f),
                                    labelStyle, valueStyle)
                            }
                        }
                    }
                }
            }
            if (state.expanded) DetailsPanel(state, callbacks)
        }
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(BitzerColors.Green))
}

@Composable
private fun SlimField(f: HeaderField, value: String, labelStyle: TextStyle, valueStyle: TextStyle) {
    Row(
        Modifier.testTag(ReportHeaderTags.field(f)).let { if (f == HeaderField.FAILURE) it.widthIn(max = FAILURE_MAX) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(fieldLabel(f).uppercase(), style = labelStyle, color = BitzerColors.Grey60, maxLines = 1, softWrap = false)
        Spacer(Modifier.size(LABEL_GAP))
        Text(value, style = valueStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun StatusChip(text: String, style: TextStyle) {
    Box(
        Modifier.testTag(ReportHeaderTags.STATUS).clip(RoundedCornerShape(50)).background(BitzerColors.Green10)
            .padding(horizontal = CHIP_PAD, vertical = 4.dp),
    ) { Text(text, style = style, color = BitzerColors.DarkGreen, maxLines = 1, softWrap = false) }
}

/** "+n": n fields moved into Details; opens the same panel as Details. 56 dp tall touch target. */
@Composable
private fun MoreChip(text: String, style: TextStyle, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 56.dp).widthIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Show hidden header fields", onClick = onClick).testTag(ReportHeaderTags.MORE),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(BitzerColors.Grey10).padding(horizontal = CHIP_PAD, vertical = 4.dp)) {
            Text(text, style = style, color = BitzerColors.DarkGreen, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun DetailsControl(expanded: Boolean, style: TextStyle, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide details" else "Show details", onClick = onClick)
            .padding(horizontal = CONTROL_PAD).testTag(ReportHeaderTags.DETAILS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DETAILS", style = style, color = BitzerColors.DarkGreen, maxLines = 1, softWrap = false)
        Spacer(Modifier.size(4.dp))
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null,
            tint = BitzerColors.DarkGreen, modifier = Modifier.size(24.dp))
    }
}

/** Full header, inline: every field exactly once (RMA number and status stay on the line above and are not repeated). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsPanel(state: ReportHeaderState, callbacks: ReportHeaderCallbacks) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag(ReportHeaderTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelField("Customer", state.customer)
            PanelField("Model", state.model)
            PanelField("Product type", state.productType)
            PanelField("Serial", state.serial)
            PanelField("Return code", state.returnCode)
            PanelField("Failed date", state.failedDate)
            PanelField("Credit status", state.creditStatus)
            PanelField("Failure code", state.failureCode)
            PanelField("Failure description", state.failureDescription)
            PanelField("Comments", state.comments)
            PanelField("RMA comment", state.rmaComment)
        }
        GuidedButtonView(state.guided, callbacks.onGuided)
    }
}

@Composable
private fun PanelField(label: String, value: String) {
    Column(Modifier.widthIn(max = 420.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = BitzerColors.Grey60)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
