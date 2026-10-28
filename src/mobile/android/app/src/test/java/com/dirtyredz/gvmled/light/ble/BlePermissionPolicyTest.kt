package com.dirtyredz.gvmled.light.ble

import com.dirtyredz.gvmled.light.connection.RadioState
import org.junit.Assert.assertEquals
import org.junit.Test

class BlePermissionPolicyTest {
    @Test fun nearbyDevicesFromApi31LocationBefore() {
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            BlePermissionPolicy.requiredPermissions(31),
        )
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), BlePermissionPolicy.requiredPermissions(30))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), BlePermissionPolicy.requiredPermissions(26))
    }

    private fun state(
        sdk: Int = 34,
        adapter: Boolean = true,
        granted: Set<String> = BlePermissionPolicy.requiredPermissions(sdk).toSet(),
        requested: Boolean = false,
        enabled: Boolean = true,
    ) = BlePermissionPolicy.radioState(sdk, adapter, { it in granted }, requested, enabled)

    @Test fun radioStatesInPriorityOrder() {
        assertEquals(RadioState.Ready, state())
        assertEquals(RadioState.Unavailable, state(adapter = false, granted = emptySet(), enabled = false))
        assertEquals(RadioState.PermissionRequired, state(granted = emptySet(), enabled = false))
        assertEquals(RadioState.PermissionDenied, state(granted = emptySet(), requested = true))
        assertEquals(
            RadioState.PermissionDenied,
            state(granted = setOf("android.permission.BLUETOOTH_SCAN"), requested = true),
        )
        assertEquals(RadioState.Off, state(enabled = false))
        assertEquals(RadioState.Ready, state(sdk = 30))
        assertEquals(RadioState.PermissionRequired, state(sdk = 30, granted = setOf("android.permission.BLUETOOTH_SCAN")))
    }
}
