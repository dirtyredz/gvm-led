package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/** One light: identity, link status, apply status of its wanted settings and its last observed (actual) state. */
@Composable
internal fun LightCard(light: LightCardModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(light.name, style = MaterialTheme.typography.titleLarge)
                    Text("…${light.addressSuffix}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(light.status, style = MaterialTheme.typography.labelLarge,
                color = if (light.isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Settings: " + light.apply, style = MaterialTheme.typography.bodyMedium)
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
}

@Composable
private fun StateLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

