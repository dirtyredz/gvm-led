package com.dirtyredz.gvmled.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.ui.components.ScreenScaffold

/** A management destination Settings links to (each later slice adds its own). */
data class SettingsLink(val title: String, val description: String, val open: () -> Unit)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (UpdateAction) -> Unit,
    links: List<SettingsLink>,
    onBack: () -> Unit,
) {
    ScreenScaffold("Settings", onBack) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("GVM LED", style = MaterialTheme.typography.titleLarge)
                    Text("Version ${state.version}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.packageName + if (state.isDebugBuild) " (debug build)" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item { UpdateSection(state.update, onAction) }
        links.forEach { link -> item { LinkCard(link.title, link.description, link.open) } }
    }
}

@Composable
private fun UpdateSection(card: UpdateCard, onAction: (UpdateAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            Text(card.message, style = MaterialTheme.typography.bodyLarge)
            card.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val progress = card.progress
            when {
                progress != null -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                card.busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                card.actions.forEachIndexed { index, action ->
                    if (index == 0) Button(onClick = { onAction(action) }) { Text(action.label) }
                    else OutlinedButton(onClick = { onAction(action) }) { Text(action.label) }
                }
            }
        }
    }
}

@Composable
private fun LinkCard(title: String, description: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
