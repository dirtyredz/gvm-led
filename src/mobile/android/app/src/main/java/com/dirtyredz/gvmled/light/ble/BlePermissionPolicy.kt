package com.dirtyredz.gvmled.light.ble

import com.dirtyredz.gvmled.light.connection.RadioState

/**
 * Android's API-specific Bluetooth permission model, as pure decisions. API 31+ uses the Nearby
 * Devices runtime permissions (scan is declared `neverForLocation`); through API 30, BLE scanning
 * needs fine location at runtime, while BLUETOOTH/BLUETOOTH_ADMIN are install-time permissions.
 */
object BlePermissionPolicy {
    const val NEARBY_DEVICES_API = 31
    const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

    fun requiredPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= NEARBY_DEVICES_API) listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
        else listOf(ACCESS_FINE_LOCATION)

    /**
     * Order matters: permission comes before the on/off check because asking the user to enable
     * Bluetooth itself needs BLUETOOTH_CONNECT on API 31+.
     */
    fun radioState(
        sdkInt: Int,
        hasAdapter: Boolean,
        isGranted: (String) -> Boolean,
        requestedBefore: Boolean,
        enabled: Boolean,
    ): RadioState = when {
        !hasAdapter -> RadioState.Unavailable
        !requiredPermissions(sdkInt).all(isGranted) ->
            if (requestedBefore) RadioState.PermissionDenied else RadioState.PermissionRequired
        !enabled -> RadioState.Off
        else -> RadioState.Ready
    }
}
