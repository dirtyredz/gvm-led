package com.dirtyredz.gvmled.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.light.connection.RadioState

/** Why Bluetooth is not usable, with the one action that fixes it. Shown on Lights and light management. */
@Composable
fun RadioBanner(radio: RadioState, actions: RadioActions) {
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
