package com.dirtyredz.gvmled.ui.components

/** Activity-owned actions: permission and Bluetooth-enable requests need the activity. */
data class RadioActions(
    val requestPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val enableBluetooth: () -> Unit,
)
