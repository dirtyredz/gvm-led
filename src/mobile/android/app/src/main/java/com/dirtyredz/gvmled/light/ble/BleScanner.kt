package com.dirtyredz.gvmled.light.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.dirtyredz.gvmled.light.connection.Advertisement
import com.dirtyredz.gvmled.light.connection.LightScanner
import com.dirtyredz.gvmled.data.model.BluetoothAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Unfiltered foreground LE scan: the lights advertise 0x180F/0x1812, not the legacy service, so no
 * service filter is possible; ConnectionCoordinator classifies by address. The `BluetoothDevice`
 * from each result is remembered so a connection uses the address type the scan reported (the
 * lights use random static addresses).
 *
 * Permission: only collected while BlePermissionPolicy reports Ready.
 */
@SuppressLint("MissingPermission")
class BleScanner(private val adapter: BluetoothAdapter) : LightScanner {
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()

    override fun scan(): Flow<Advertisement> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("LE scanner unavailable (Bluetooth off?)")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                devices[BluetoothAddress.normalize(result.device.address)] = result.device
                trySend(
                    Advertisement(
                        address = result.device.address,
                        name = result.scanRecord?.deviceName,
                        rssi = result.rssi,
                        serviceUuids = result.scanRecord?.serviceUuids?.map { it.toString() }.orEmpty(),
                    ),
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) =
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed (error $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /** The scanned device when seen this process, else one built from the address. */
    fun deviceFor(address: String): BluetoothDevice =
        devices[BluetoothAddress.normalize(address)] ?: adapter.getRemoteDevice(BluetoothAddress.normalize(address))
}
