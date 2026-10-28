package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.R
import com.dirtyredz.gvmled.connection.RadioState

/** Activity-owned actions: permission and Bluetooth-enable requests need the activity. */
data class RadioActions(
    val requestPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val enableBluetooth: () -> Unit,
)

@Composable
fun LightsScreen(state: LightsUiState, radioActions: RadioActions, onRename: (String, String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (state.radio != RadioState.Ready) item { RadioBanner(state.radio, radioActions) }
        items(state.lights, key = { it.address }) { light -> LightCard(light, onRename) }
        if (state.otherLights.isNotEmpty()) {
            item {
                Text(
                    "Other BT_LED devices nearby: " + state.otherLights.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun RadioBanner(radio: RadioState, actions: RadioActions) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(radioMessage(radio))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (radio) {
                    RadioState.PermissionRequired ->
                        Button(onClick = actions.requestPermissions) { Text("Allow") }
                    RadioState.PermissionDenied -> {
                        Button(onClick = actions.requestPermissions) { Text("Try again") }
                        OutlinedButton(onClick = actions.openAppSettings) { Text("App settings") }
                    }
                    RadioState.Off -> Button(onClick = actions.enableBluetooth) { Text("Turn on") }
                    RadioState.Unavailable, RadioState.Ready -> Unit
                }
            }
        }
    }
}

private fun radioMessage(radio: RadioState): String = when (radio) {
    RadioState.Unavailable -> "This phone has no Bluetooth LE adapter."
    RadioState.PermissionRequired -> "Allow Nearby devices so the app can find and connect to your lights."
    RadioState.PermissionDenied -> "Bluetooth permission was denied. Allow it to see your lights."
    RadioState.Off -> "Bluetooth is off."
    RadioState.Ready -> ""
}
