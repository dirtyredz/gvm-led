package com.dirtyredz.gvmled.light.connection

import com.dirtyredz.gvmled.light.protocol.Crc16Xmodem
import com.dirtyredz.gvmled.light.protocol.hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/** Captured vectors (protocol.md / live-test-20261026-001355.log), shared by the connection tests. */
internal object Vectors {
    val HELLO_REQUEST = hex("4C 54 09 00 00 53 00 00 01 00 94 74 FF")
    val HELLO_REPLY = hex("4C 54 0A 00 33 53 00 00 02 20 38 F3 9B")
    /** Light 1: on, HSI, hue 71 (355 deg), sat 100, brightness 7. */
    val REPORT_1 = hex("4C 54 0E 00 33 03 01 0B 07 2C 47 64 02 01 01 86 9C")
    val ACK_1 = hex("4C 54 0E 00 33 48 01 0B 07 2C 47 64 02 01 01 0F 02 FF")
    /** Light 2: on, HSI, hue 48 (240 deg), sat 100, brightness 34. */
    val REPORT_2 = hex("4C 54 0E 00 33 03 01 0B 22 24 30 64 02 01 01 E6 DD")
    val SHORT_REPLY = hex("4C 54 0B 00 33 02 00 00 01 0B 07 2C 64 61")

    /** Test input only: frames [body] (header through payload) with a valid CRC. */
    fun framed(body: String): ByteArray {
        val bytes = hex(body)
        val crc = Crc16Xmodem.calculate(bytes)
        return bytes + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
    }
}

/** Scriptable transport. Every call is appended to the shared [log] so tests can assert ordering. */
internal class FakeLightTransport(
    val address: String,
    private val log: MutableList<String>,
    private val now: () -> Long,
) : LightTransport {
    private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<TransportEvent> get() = channel

    var connectGate: CompletableDeferred<Unit>? = null
    var subscribeGate: CompletableDeferred<Unit>? = null
    var connectFailure: String? = null
    /** Reply to every hello write with the captured hello reply. */
    var answerHello = true
    /** Called with every written packet after it is logged (a simulated light hooks in here). */
    var onWrite: (ByteArray) -> Unit = {}

    val writes = mutableListOf<Pair<Long, ByteArray>>()
    var closed = false
        private set

    override suspend fun connect() {
        log += "$address connect"
        connectGate?.await()
        connectFailure?.let { throw TransportException(it) }
    }

    override suspend fun subscribe() {
        log += "$address subscribe"
        subscribeGate?.await()
        log += "$address subscribed"
    }

    override suspend fun write(packet: ByteArray) {
        log += "$address write"
        writes += now() to packet
        if (answerHello && packet.contentEquals(Vectors.HELLO_REQUEST)) notify(Vectors.HELLO_REPLY)
        onWrite(packet)
    }

    override fun close() {
        if (!closed) log += "$address close"
        closed = true
        channel.close()
    }

    /** [receivedAt] defaults to now; an earlier stamp models a notification queued before processing. */
    fun notify(bytes: ByteArray, receivedAt: Long = now()) {
        channel.trySend(TransportEvent.Notification(bytes, receivedAt))
    }

    fun disconnect(reason: String) {
        channel.trySend(TransportEvent.Disconnected(reason))
    }
}

internal class FakeTransports(private val now: () -> Long) : LightTransportFactory {
    val log = mutableListOf<String>()
    val created = mutableListOf<FakeLightTransport>()
    var configure: (FakeLightTransport) -> Unit = {}

    override fun create(address: String): LightTransport {
        log += "$address create"
        return FakeLightTransport(address, log, now).also { configure(it); created += it }
    }

    fun latest(address: String): FakeLightTransport = created.last { it.address == address }
}

internal class FakeScanner : LightScanner {
    val advertisements = MutableSharedFlow<Advertisement>(extraBufferCapacity = 16)
    var activeScans = 0
        private set

    override fun scan(): Flow<Advertisement> = flow {
        activeScans++
        try {
            advertisements.collect { emit(it) }
        } finally {
            activeScans--
        }
    }
}
