package com.artemchep.keyguard.ipctestclient.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
fun <T> Dropdown(
    label: String,
    items: List<T>,
    selected: T,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        OutlinedButton(onClick = { expanded = true }) {
            Text(itemLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

@Composable
fun TextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Three-state control for the boolean extras.
 *
 * Omitting the extra is not the same as sending `false`: the provider defaults
 * `enable_compression` to `true`, and every extra feeds the approval digest.
 */
@Composable
fun TriStateRow(
    label: String,
    value: Boolean?,
    onValueChange: (Boolean?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf<Boolean?>(null, true, false).forEach { option ->
                FilterChip(
                    selected = value == option,
                    onClick = { onValueChange(option) },
                    label = { Text(option?.toString() ?: "omit") },
                )
            }
        }
    }
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.horizontalScroll(rememberScrollState()),
    )
}
