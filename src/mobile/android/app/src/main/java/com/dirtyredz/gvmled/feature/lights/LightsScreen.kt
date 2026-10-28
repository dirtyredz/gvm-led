package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.R
import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.light.control.LightEdit
import com.dirtyredz.gvmled.ui.components.RadioActions
import com.dirtyredz.gvmled.ui.components.RadioBanner
import com.dirtyredz.gvmled.ui.components.ScreenScaffold

/** What the Lights screen can ask for: change selection, apply an edit, reset unreadable settings. */
class LightsActions(
    val select: (ControlTarget) -> Unit,
    val apply: (LightEdit) -> Unit,
    val resetSettings: () -> Unit,
)

@Composable
fun LightsScreen(
    state: LightsUiState,
    radioActions: RadioActions,
    actions: LightsActions,
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
            item { TargetSelector(state.targets, actions.select) }
            state.groupSummary?.let { summary -> item { Text(summary, style = MaterialTheme.typography.titleMedium) } }
            state.settingsProblem?.let { problem -> item { SettingsProblem(problem, actions.resetSettings) } }
            val controls = state.controls
            if (controls != null && state.settingsProblem == null) {
                item { LightControls(controls, state.outcome, state.message, actions.apply) }
            }
        }
        items(state.lights, key = { it.address }) { light -> LightCard(light) }
    }
}

@Composable
private fun SettingsProblem(problem: String, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$problem. Controls are paused so nothing is overwritten.")
            TextButton(onClick = onReset) { Text("Discard saved settings and adopt the lights' current state") }
        }
    }
}
