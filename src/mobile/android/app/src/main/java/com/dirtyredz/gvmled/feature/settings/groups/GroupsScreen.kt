package com.dirtyredz.gvmled.feature.settings.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.ui.components.ScreenScaffold

/** The saved groups; tapping one edits it. There is no automatic "Both" or "All lights" group. */
@Composable
fun GroupsScreen(state: GroupsUiState, onCreate: () -> Unit, onEdit: (String) -> Unit, onBack: () -> Unit) {
    ScreenScaffold("Groups", onBack) {
        item {
            Button(onClick = onCreate, enabled = state.canCreate) { Text("Create group") }
        }
        if (!state.canCreate) item { Text("Add a light first (Settings, then Lights).", style = MaterialTheme.typography.bodyMedium) }
        if (state.groups.isEmpty() && state.canCreate) item { Text("No groups yet.", style = MaterialTheme.typography.bodyMedium) }
        items(state.groups, key = { it.id }) { group ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(group.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Text(group.members, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
