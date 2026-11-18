package com.bitzer.rmainspector.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.CodePicking
import com.bitzer.rmainspector.data.PickerRow
import com.bitzer.rmainspector.ui.theme.BitzerColors

/**
 * Closed state of a code dropdown: a 56 dp field showing the label and the chosen code (or [placeholder]).
 * [selected] is shown even when it is no longer offered (hidden codes on existing rows).
 */
@Composable
fun DropdownField(
    label: String,
    selected: Code?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Tap to choose",
    enabled: Boolean = true,
) {
    val border = if (enabled) BitzerColors.Grey60 else BitzerColors.Grey30
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
        Spacer(Modifier.size(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .border(1.dp, border, RoundedCornerShape(4.dp))
                .clickable(enabled = enabled, role = Role.DropdownList, onClick = onClick)
                .semantics { contentDescription = "$label: ${selected?.let { codeText(it) } ?: placeholder}" }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Text(selected.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = BitzerColors.DarkGreen, modifier = Modifier.widthIn(min = 48.dp))
                Spacer(Modifier.width(12.dp))
                Text(selected.description, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = BitzerColors.Grey60, modifier = Modifier.weight(1f))
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = if (enabled) BitzerColors.Grey90 else BitzerColors.Grey30)
        }
    }
}

private fun codeText(c: Code) = if (c.group.isNullOrEmpty()) c.label else "${c.group} · ${c.label}"

/**
 * Open state of a code dropdown, stateless: search box (code or description), "recently used" strip and a
 * lazily drawn list (fine for hundreds of codes). [rows] come from [CodePicking.rows] on the filtered list.
 */
@Composable
fun CodePickerList(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    rows: List<PickerRow>,
    recent: List<Code>,
    selected: CodeKey?,
    onSelect: (Code) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onClear != null && selected != null) TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 56.dp)) { Text("Clear") }
            if (onClose != null) TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp).height(2.dp).background(BitzerColors.Green))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") } }
            } else null,
            placeholder = { Text("Search code or description") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 56.dp),
        )
        if (recent.isNotEmpty() && query.isBlank()) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, contentDescription = null, tint = BitzerColors.Grey60, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("RECENTLY USED", style = MaterialTheme.typography.labelMedium, color = BitzerColors.Grey90)
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 24.dp),
            ) {
                items(recent, key = { "r|${it.group}|${it.code}" }) { c ->
                    AssistChip(
                        onClick = { onSelect(c) },
                        label = { Text(c.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        val count = rows.count { it is PickerRow.Item }
        Text(
            if (count == 1) "1 code" else "$count codes",
            style = MaterialTheme.typography.labelMedium, color = BitzerColors.Grey60,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = BitzerColors.Grey30)
        if (count == 0) {
            Text("No code matches \"${query.trim()}\".", style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(rows, key = { r ->
                    when (r) {
                        is PickerRow.Header -> "h|${r.group}"
                        is PickerRow.Item -> "c|${r.code.group}|${r.code.code}"
                    }
                }, contentType = { it::class }) { r ->
                    when (r) {
                        is PickerRow.Header -> Text(
                            r.group.uppercase(),
                            style = MaterialTheme.typography.labelLarge, color = BitzerColors.DarkGreen,
                            modifier = Modifier.fillMaxWidth().background(BitzerColors.Grey10).padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                        is PickerRow.Item -> CodeRow(r.code, r.pinned, r.code.key == selected) { onSelect(r.code) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeRow(code: Code, pinned: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (isSelected) BitzerColors.Green10 else BitzerColors.White)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(code.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = BitzerColors.DarkGreen, modifier = Modifier.width(88.dp))
        Text(code.description, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (pinned) Icon(Icons.Filled.PushPin, contentDescription = "Listed first", tint = BitzerColors.Grey60, modifier = Modifier.size(18.dp))
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = BitzerColors.Green)
        }
    }
    HorizontalDivider(color = BitzerColors.Grey10)
}

/**
 * Shared searchable code dropdown (spec §4.3, §5). Closed: [DropdownField]. Open: a dialog with
 * [CodePickerList]. [items] are code objects as received; [onSelect] returns the same object, so its
 * `group` + `code` go back to the server untouched. [pinned] = e.g. `normal_issue_codes` for issues.
 */
@Composable
fun SearchableDropdown(
    label: String,
    items: List<Code>,
    selected: Code?,
    onSelect: (Code) -> Unit,
    modifier: Modifier = Modifier,
    pinned: List<CodeKey> = emptyList(),
    hidden: List<CodeKey> = emptyList(),
    recent: List<CodeKey> = emptyList(),
    enabled: Boolean = true,
    placeholder: String = "Tap to choose",
    onClear: (() -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    DropdownField(label, selected, onClick = { open = true }, modifier = modifier, placeholder = placeholder, enabled = enabled)
    if (open) {
        var query by rememberSaveable { mutableStateOf("") }
        val arranged = remember(items, pinned, hidden) { CodePicking.arrange(items, pinned, hidden) }
        val filtered = remember(arranged, query) { CodePicking.filter(arranged, query) }
        val rows = remember(filtered, pinned, query) { CodePicking.rows(filtered, pinned, withHeaders = query.isBlank()) }
        val recentCodes = remember(arranged, recent) { CodePicking.recent(arranged, recent) }
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = BitzerColors.White,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.92f).fillMaxHeight(0.9f),
            ) {
                CodePickerList(
                    title = label, query = query, onQueryChange = { query = it }, rows = rows, recent = recentCodes,
                    selected = selected?.key,
                    onSelect = { onSelect(it); open = false; query = "" },
                    onClose = { open = false },
                    onClear = onClear?.let { c -> { c(); open = false } },
                )
            }
        }
    }
}
