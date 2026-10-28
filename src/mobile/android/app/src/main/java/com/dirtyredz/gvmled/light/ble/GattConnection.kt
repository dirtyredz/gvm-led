package com.dirtyredz.gvmled.light.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.dirtyredz.gvmled.light.connection.LightTransport
import com.dirtyredz.gvmled.light.connection.TransportEvent
import com.dirtyredz.gvmled.light.connection.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Android [LightTransport]: one `BluetoothGatt` for one generation. Callbacks arrive on a binder
 * thread; after [close] (or from any other `BluetoothGatt`) they are ignored. Operations are
 * serialized through [GattOperationQueue]. Packets are at most 18 bytes, so the default 23-byte
 * MTU carries each in one write and no MTU request is made.
 *
 * Permission: only created while BlePermissionPolicy reports Ready; a revoked permission surfaces
 * as a SecurityException, which becomes a TransportException.
 */
@SuppressLint("MissingPermission")
class GattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
) : LightTransport {
    private val incoming = Channel<TransportEvent>(EVENT_CAPACITY, BufferOverflow.DROP_OLDEST)
    override val events: ReceiveChannel<TransportEvent> get() = incoming

    private val queue = GattOperationQueue(OPERATION_TIMEOUT_MS)
    private val connected = CompletableDeferred<Unit>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    private var characteristic: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (ignored(g)) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val reason = "Disconnected (GATT status $status)"
                connected.completeExceptionally(TransportException(reason))
                queue.failPending(reason)
                incoming.trySend(TransportEvent.Disconnected(reason))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!ignored(g)) queue.complete(GattOperation.DiscoverServices, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (!ignored(g)) queue.complete(GattOperation.WriteDescriptor, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (!ignored(g)) queue.complete(GattOperation.WriteCharacteristic, status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            deliver(g, c, value)

        // Before API 33 only this overload is called; from API 33 the one above replaces it.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = c.value
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && value != null) {
                deliver(g, c, value)
            }
        }
    }

    private fun ignored(g: BluetoothGatt): Boolean = closed || (gatt != null && g !== gatt)

    private fun deliver(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (!ignored(g) && c.uuid == LegacyGattProfile.CHARACTERISTIC) {
            incoming.trySend(TransportEvent.Notification(value.copyOf()))
        }
    }

    override suspend fun connect() {
        val g = guarded {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } ?: throw TransportException("Could not create a GATT connection")
        gatt = g
        connected.await()
        val status = queue.execute(GattOperation.DiscoverServices) { guarded { g.discoverServices() } }
        if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Service discovery failed ($status)")
        val found = g.getService(LegacyGattProfile.SERVICE)?.getCharacteristic(LegacyGattProfile.CHARACTERISTIC)
            ?: throw TransportException("Legacy BT_LED service not found")
        if (!LegacyGattProfile.isUsable(found.properties)) {
            throw TransportException("Legacy characteristic lacks notify/write-without-response")
        }
        characteristic = found
    }

    override suspend fun subscribe() {
        val g = gatt ?: throw TransportException("Not connected")
        val c = characteristic ?: throw TransportException("Not connected")
        if (!guarded { g.setCharacteristicNotification(c, true) }) {
            throw TransportException("Could not enable notifications")
        }
        val descriptor = c.getDescriptor(LegacyGattProfile.CLIENT_CONFIG)
            ?: throw TransportException("Notification descriptor missing")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val status = queue.execute(GattOperation.WriteDescriptor) {
            guarded {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Subscription failed ($status)")
    }

    override suspend fun write(packet: ByteArray) {
        val g = gatt ?: throw TransportException("Not connected")
        val c = characteristic ?: throw TransportException("Not connected")
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val status = queue.execute(GattOperation.WriteCharacteristic) {
            guarded {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(c, packet, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    c.writeType = type
                    @Suppress("DEPRECATION")
                    c.value = packet
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw TransportException("Write failed ($status)")
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.failPending("Connection closed")
        connected.completeExceptionally(TransportException("Connection closed"))
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
        incoming.close()
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (denied: SecurityException) {
        throw TransportException("Bluetooth permission missing: ${denied.message}")
    }

    private companion object {
        const val EVENT_CAPACITY = 64
        const val OPERATION_TIMEOUT_MS = 5_000L
    }
}
