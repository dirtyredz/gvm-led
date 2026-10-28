package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.data.repository.LightsRepository

/** One light's identity, link status and last observed state (read-only). */
@Composable
internal fun LightCard(light: LightCardModel, onRename: (String, String) -> Unit) {
    var renaming by rememberSaveable(light.address) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(light.name, style = MaterialTheme.typography.titleLarge)
                    Text("…${light.addressSuffix}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { renaming = true }) { Text("Rename") }
            }
            Text(light.status, style = MaterialTheme.typography.labelLarge,
                color = if (light.isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            val observed = light.observed
            if (observed == null) {
                Text("No state report yet", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(Modifier.alpha(if (light.isStale) 0.5f else 1f)) {
                    StateLine("Power", observed.power)
                    StateLine("Mode", observed.mode)
                    StateLine(observed.colourLabel, observed.colour)
                    StateLine("Brightness", observed.brightness)
                    Text(
                        observed.updated + if (light.isStale) " (last known)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            light.cctRange?.let { Text("CCT range $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (renaming) {
        RenameDialog(
            current = if (light.isCustomName) light.name else "",
            onDismiss = { renaming = false },
            onSave = { onRename(light.address, it); renaming = false },
        )
    }
}

@Composable
private fun StateLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename light") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(LightsRepository.MAX_NAME_LENGTH) },
                singleLine = true,
                label = { Text("Name (blank for default)") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
