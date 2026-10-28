package com.dirtyredz.gvmled.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dirtyredz.gvmled.connection.RadioState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Publishes whether Bluetooth is usable (adapter, permissions, on/off) as [state]. Adapter changes
 * arrive by broadcast; permission changes are re-read on [refresh] (the activity calls it on start
 * and after a permission request).
 */
class BluetoothRadio(private val context: Context, val adapter: BluetoothAdapter?) {
    private var requestedBefore = false
    private val mutableState = MutableStateFlow(evaluate())
    val state: StateFlow<RadioState> = mutableState.asStateFlow()

    val requiredPermissions: List<String> get() = BlePermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT)

    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun refresh() {
        mutableState.value = evaluate()
    }

    /** A permission request finished; anything still missing now counts as denied. */
    fun permissionsRequested() {
        requestedBefore = true
        refresh()
    }

    private fun evaluate(): RadioState = BlePermissionPolicy.radioState(
        sdkInt = Build.VERSION.SDK_INT,
        hasAdapter = adapter != null,
        isGranted = { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
        requestedBefore = requestedBefore,
        enabled = adapter?.isEnabled == true,
    )
}
