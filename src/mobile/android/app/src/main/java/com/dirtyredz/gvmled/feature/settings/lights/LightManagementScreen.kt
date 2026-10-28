package com.dirtyredz.gvmled.feature.settings.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.feature.lights.addressSuffix
import com.dirtyredz.gvmled.ui.components.NameDialog
import com.dirtyredz.gvmled.ui.components.RadioActions
import com.dirtyredz.gvmled.ui.components.RadioBanner
import com.dirtyredz.gvmled.ui.components.ScreenScaffold

/** What the management screen can ask its ViewModel to do. */
data class LightManagementActions(
    val find: () -> Unit,
    val add: (address: String, name: String) -> Unit,
    val rename: (address: String, name: String) -> Unit,
    val resetSaved: () -> Unit,
    val dismissMessage: () -> Unit,
)

@Composable
fun LightManagementScreen(
    state: LightManagementUiState,
    actions: LightManagementActions,
    radioActions: RadioActions,
    onBack: () -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenScaffold("Lights", onBack) {
        if (state.radio != RadioState.Ready) item { RadioBanner(state.radio, radioActions) }
        state.problem?.let { problem -> item { ProblemCard(problem, actions.resetSaved) } }
        state.message?.let { message ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.dismissMessage) { Text("OK") }
                }
            }
        }
        item { Text("Saved lights", style = MaterialTheme.typography.titleMedium) }
        if (state.lights.isEmpty()) item { Text("None yet.", style = MaterialTheme.typography.bodyMedium) }
        items(state.lights, key = { it.address }) { light ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(light.name, style = MaterialTheme.typography.titleMedium)
                        Text("…${light.addressSuffix} · ${light.status}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { renaming = light.address }) { Text("Rename") }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Nearby BT_LED devices", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Adding a light only saves its address. The app then connects and checks that it answers " +
                        "the GVM protocol; it never changes the light while adding it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = actions.find, enabled = !state.finding && state.radio == RadioState.Ready) {
                    Text(if (state.finding) "Searching nearby…" else "Find lights")
                }
            }
        }
        if (state.candidates.isEmpty()) {
            item { Text(if (state.finding) "Nothing new seen yet." else "No new devices seen.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.candidates, key = { "candidate:" + it.address }) { candidate ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(candidate.address, style = MaterialTheme.typography.titleSmall)
                        Text(candidate.seen, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { adding = candidate.address }) { Text("Add") }
                }
            }
        }
    }
    renaming?.let { address ->
        val current = state.lights.firstOrNull { it.address == address }?.name.orEmpty()
        NameDialog("Rename light", current, "Save", onDismiss = { renaming = null }) {
            actions.rename(address, it)
            renaming = null
        }
    }
    adding?.let { address ->
        NameDialog("Add light …${addressSuffix(address)}", state.suggestedName, "Add", onDismiss = { adding = null }) {
            actions.add(address, it)
            adding = null
        }
    }
}

@Composable
private fun ProblemCard(problem: String, onReset: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$problem. Changes are paused so nothing is overwritten.")
            if (!confirming) {
                OutlinedButton(onClick = { confirming = true }) { Text("Reset saved lights…") }
            } else {
                Text("This replaces the saved lights and groups with the two default lights.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onReset(); confirming = false }) { Text("Reset") }
                    TextButton(onClick = { confirming = false }) { Text("Cancel") }
                }
            }
        }
    }
}
