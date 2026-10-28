package com.dirtyredz.gvmled.light.connection

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The seam between connection logic and the platform BLE stack: one physical link to one light.
 * The Android adapter is `ble/GattConnection`; tests use a fake. An instance is one connection
 * generation: once [close] is called it is retired and never reused.
 */
interface LightTransport {
    /** Notifications and disconnection, in arrival order. Only the owning session consumes it. */
    val events: ReceiveChannel<TransportEvent>

    /** Connects, discovers services and validates the legacy service and characteristic. */
    suspend fun connect()

    /** Enables notifications locally, writes the CCCD and returns once the descriptor write completed. */
    suspend fun subscribe()

    /** Writes one complete packet, write-without-response. */
    suspend fun write(packet: ByteArray)

    /** Disconnects and releases the platform connection. Idempotent; later callbacks are ignored. */
    fun close()
}

sealed interface TransportEvent {
    /** [receivedAtMillis] is the monotonic time the platform delivered it, stamped at ingress. */
    class Notification(val bytes: ByteArray, val receivedAtMillis: Long) : TransportEvent
    data class Disconnected(val reason: String) : TransportEvent
}

/** Any transport failure: connect, discovery, validation, subscription or write. */
class TransportException(message: String) : Exception(message)

/** Creates a fresh transport (a new connection generation) for one address. */
fun interface LightTransportFactory {
    fun create(address: String): LightTransport
}
