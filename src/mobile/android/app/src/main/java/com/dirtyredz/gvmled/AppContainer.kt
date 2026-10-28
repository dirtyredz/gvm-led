package com.dirtyredz.gvmled

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dirtyredz.gvmled.ble.BleScanner
import com.dirtyredz.gvmled.ble.BluetoothRadio
import com.dirtyredz.gvmled.ble.GattConnection
import com.dirtyredz.gvmled.connection.ConnectionCoordinator
import com.dirtyredz.gvmled.connection.LightScanner
import com.dirtyredz.gvmled.connection.LightTransportFactory
import com.dirtyredz.gvmled.data.local.LightPreferences
import com.dirtyredz.gvmled.data.repository.LightsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow

/**
 * Explicit dependency construction (no DI framework). One instance per process, owned by
 * [GvmApplication]; the activity and ViewModels take what they need from here.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    val radio = BluetoothRadio(app, adapter)
    private val scanner: BleScanner? = adapter?.let(::BleScanner)

    val preferences = LightPreferences(app.getSharedPreferences("lights", Context.MODE_PRIVATE))

    // Without an adapter the radio stays Unavailable, so neither fallback is ever used.
    val coordinator = ConnectionCoordinator(
        addresses = preferences.addresses,
        scanner = scanner ?: LightScanner { emptyFlow() },
        transports = LightTransportFactory { address ->
            GattConnection(app, checkNotNull(scanner) { "No Bluetooth adapter" }.deviceFor(address))
        },
        radio = radio.state,
        scope = scope,
        monotonicMillis = SystemClock::elapsedRealtime,
        log = { Log.i(LOG_TAG, it) },
    )

    val lights = LightsRepository(coordinator.lights, coordinator.otherLights, preferences)

    private companion object {
        const val LOG_TAG = "GvmLed"
    }
}
