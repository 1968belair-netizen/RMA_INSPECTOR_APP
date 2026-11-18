package com.bitzer.rmainspector.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.ui.theme.BitzerColors

/**
 * Shared multi-select chip row (stateless). [options] are code objects as received, shown in the order given
 * (sort with [com.bitzer.rmainspector.data.CodePicking.arrange] first if they should be natural-sorted).
 * [onToggle] returns the same object, so its `group` + `code` go back unchanged. Wraps onto more lines.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiSelectChipRow(
    options: List<Code>,
    selected: Set<CodeKey>,
    onToggle: (Code) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    chipText: (Code) -> String = { it.description.ifBlank { it.code } },
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = BitzerColors.Grey90)
            Spacer(Modifier.size(4.dp))
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                val isOn = option.key in selected
                FilterChip(
                    selected = isOn,
                    onClick = { onToggle(option) },
                    enabled = enabled,
                    label = { Text(chipText(option)) },
                    leadingIcon = if (isOn) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BitzerColors.Green25,
                        selectedLabelColor = BitzerColors.Black,
                        selectedLeadingIconColor = BitzerColors.DarkGreen,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

/** Toggle helper for callers holding a selection: returns the new set (keys exactly as received). */
fun Set<CodeKey>.toggled(code: Code): Set<CodeKey> = if (code.key in this) this - code.key else this + code.key
