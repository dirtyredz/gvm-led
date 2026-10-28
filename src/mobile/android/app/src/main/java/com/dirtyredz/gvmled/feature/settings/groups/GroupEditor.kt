package com.dirtyredz.gvmled.feature.settings.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.ui.components.ScreenScaffold

data class GroupEditorActions(
    val setName: (String) -> Unit,
    val toggle: (String) -> Unit,
    val save: () -> Unit,
    val delete: (String) -> Unit,
    val cancel: () -> Unit,
)

/** Create or edit one group: a name and a multi-select of known lights (offline ones included). */
@Composable
fun GroupEditor(editor: GroupEditorState, actions: GroupEditorActions) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(if (editor.groupId == null) "New group" else "Edit group", onBack = actions.cancel) {
        item {
            OutlinedTextField(
                value = editor.name,
                onValueChange = actions.setName,
                singleLine = true,
                label = { Text("Group name") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Text("Lights", style = MaterialTheme.typography.titleMedium) }
        items(editor.options, key = { it.address }) { option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = option.selected, onCheckedChange = { actions.toggle(option.address) })
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    Text(option.availability, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        editor.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.save) { Text("Save") }
                TextButton(onClick = actions.cancel) { Text("Cancel") }
                if (editor.groupId != null) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    }
    val id = editor.groupId
    if (confirmDelete && id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${editor.name}\"?") },
            text = { Text("The group is removed. Its lights and your other groups stay as they are.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.delete(id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
