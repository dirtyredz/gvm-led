package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.R
import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.ui.components.RadioActions
import com.dirtyredz.gvmled.ui.components.RadioBanner
import com.dirtyredz.gvmled.ui.components.ScreenScaffold

@Composable
fun LightsScreen(
    state: LightsUiState,
    radioActions: RadioActions,
    onSelect: (ControlTarget) -> Unit,
    onOpenSettings: () -> Unit,
) {
    ScreenScaffold(
        title = stringResource(R.string.app_name),
        onBack = null,
        trailing = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
    ) {
        if (state.radio != RadioState.Ready) item { RadioBanner(state.radio, radioActions) }
        if (state.isEmpty) {
            item {
                Text(
                    "No lights yet. Open Settings, then Lights, to find and add one.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            item { TargetSelector(state.targets, onSelect) }
            state.groupSummary?.let { summary -> item { Text(summary, style = MaterialTheme.typography.titleMedium) } }
        }
        items(state.lights, key = { it.address }) { light -> LightCard(light) }
    }
}

/** Every individual light, then every named group. Selection is read-only until controls arrive. */
@Composable
private fun TargetSelector(targets: List<TargetChip>, onSelect: (ControlTarget) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        targets.forEach { target ->
            FilterChip(
                selected = target.selected,
                onClick = { onSelect(target.target) },
                label = { Text(if (target.isGroup) "${target.label} (group)" else target.label) },
            )
        }
    }
}
