package com.dirtyredz.gvmled.connection

import com.dirtyredz.gvmled.protocol.FullStateReport
import com.dirtyredz.gvmled.protocol.HelloReply
import com.dirtyredz.gvmled.protocol.LegacyFrameDecoder
import com.dirtyredz.gvmled.protocol.LegacyMessage
import com.dirtyredz.gvmled.protocol.LegacyPacketEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Handshake and write timing. Defaults follow protocol.md and the reference client. */
data class SessionTiming(
    val connectTimeoutMs: Long = 15_000,
    val subscribeTimeoutMs: Long = 5_000,
    /** The official app re-sends hello every second until answered. */
    val helloIntervalMs: Long = 1_000,
    val helloAttempts: Int = 5,
    /** Initial pacing between writes on one link. */
    val writeSpacingMs: Long = 200,
    val writeTimeoutMs: Long = 2_000,
)

/** How one connection generation ended. */
data class SessionOutcome(val reachedLive: Boolean, val reason: String)

/**
 * The single owner of one light's link. At most one [LightTransport] exists per session at a time:
 * each [connectOnce] call is a new generation that creates, uses and always closes its transport.
 * Read-only: the only writes are hello and report acknowledgements; set commands come later.
 *
 * Wire input is untrusted: the decoder drops corrupt frames, device identity comes only from a
 * valid hello, and a report whose identity differs from that hello is ignored.
 */
class LightSession(
    address: String,
    private val transports: LightTransportFactory,
    private val monotonicMillis: () -> Long,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val timing: SessionTiming = SessionTiming(),
    private val log: (String) -> Unit = {},
) {
    val address: String = normalizeAddress(address)

    private val mutableState = MutableStateFlow(LightConnection(this.address))
    val state: StateFlow<LightConnection> = mutableState.asStateFlow()

    private val decoder = LegacyFrameDecoder()
    private var lastWriteAt: Long? = null

    fun markIdle() = mutableState.update { it.copy(status = LinkStatus.Idle) }

    fun markSearching() = mutableState.update { it.copy(status = LinkStatus.Searching) }

    fun markWaiting(reason: String) =
        mutableState.update { it.copy(status = LinkStatus.Waiting, lastError = reason) }

    /**
     * Runs one connection generation until it ends. Establishment (connect, subscribe, hello) holds
     * [establish] so lights connect one after another; the live phase runs without it. Cancellation
     * closes the transport and leaves the status for the caller to set.
     */
    suspend fun connectOnce(establish: Mutex): SessionOutcome {
        val generation = state.value.generation + 1
        mutableState.update {
            it.copy(status = LinkStatus.Connecting, generation = generation, hello = null, lastError = null)
        }
        decoder.reset()
        var transport: LightTransport? = null
        var reachedLive = false
        try {
            val pending = ArrayDeque<LegacyMessage>()
            val (link, hello) = establish.withLock {
                val created = transports.create(address).also { transport = it }
                created to openLink(created, pending)
            }
            reachedLive = true
            mutableState.update { it.copy(status = LinkStatus.Live, hello = hello) }
            log("$address live: type ${hello.deviceType}, ${hello.minCctKelvin}-${hello.maxCctKelvin} K")
            return SessionOutcome(reachedLive = true, reason = receive(link, hello, generation, pending))
        } catch (failure: TransportException) {
            log("$address generation $generation failed: ${failure.message}")
            return SessionOutcome(reachedLive, failure.message ?: "Connection failed")
        } finally {
            transport?.close()
            decoder.reset()
        }
    }

    private suspend fun openLink(transport: LightTransport, pending: ArrayDeque<LegacyMessage>): HelloReply {
        withTimeoutOrNull(timing.connectTimeoutMs) { transport.connect() }
            ?: throw TransportException("Connection timed out")
        withTimeoutOrNull(timing.subscribeTimeoutMs) { transport.subscribe() }
            ?: throw TransportException("Subscription timed out")
        mutableState.update { it.copy(status = LinkStatus.Handshaking) }
        repeat(timing.helloAttempts) {
            write(transport, LegacyPacketEncoder.hello())
            withTimeoutOrNull(timing.helloIntervalMs) { awaitHello(transport, pending) }?.let { return it }
        }
        throw TransportException("No valid hello reply after ${timing.helloAttempts} attempts")
    }

    /** Messages decoded after the hello in the same notification are kept for the live phase. */
    private suspend fun awaitHello(transport: LightTransport, pending: ArrayDeque<LegacyMessage>): HelloReply {
        while (true) {
            val event = transport.events.receiveCatching().getOrNull() ?: throw TransportException("Link closed")
            when (event) {
                is TransportEvent.Disconnected -> throw TransportException(event.reason)
                is TransportEvent.Notification -> {
                    val messages = decoder.feed(event.bytes)
                    val index = messages.indexOfFirst { it is HelloReply }
                    if (index >= 0) {
                        pending.addAll(messages.subList(index + 1, messages.size))
                        return messages[index] as HelloReply
                    }
                }
            }
        }
    }

    private suspend fun receive(
        transport: LightTransport,
        hello: HelloReply,
        generation: Int,
        pending: ArrayDeque<LegacyMessage>,
    ): String {
        while (pending.isNotEmpty()) handle(transport, hello, generation, pending.removeFirst())
        for (event in transport.events) {
            when (event) {
                is TransportEvent.Disconnected -> return event.reason
                is TransportEvent.Notification ->
                    decoder.feed(event.bytes).forEach { handle(transport, hello, generation, it) }
            }
        }
        return "Link closed"
    }

    /** Only full reports matching the hello identity change state; short replies never do. */
    private suspend fun handle(transport: LightTransport, hello: HelloReply, generation: Int, message: LegacyMessage) {
        if (message !is FullStateReport) return
        if (message.deviceId != hello.deviceId || message.deviceType != hello.deviceType) {
            log("$address ignored a report for device ${message.deviceId}/${message.deviceType}")
            return
        }
        mutableState.update {
            it.copy(report = message, reportGeneration = generation, reportAtMillis = wallMillis())
        }
        try {
            write(transport, LegacyPacketEncoder.acknowledge(message))
        } catch (failure: TransportException) {
            log("$address acknowledgement failed: ${failure.message}")
        }
    }

    private suspend fun write(transport: LightTransport, packet: ByteArray) {
        lastWriteAt?.let { previous ->
            val wait = previous + timing.writeSpacingMs - monotonicMillis()
            if (wait > 0) delay(wait)
        }
        lastWriteAt = monotonicMillis()
        withTimeoutOrNull(timing.writeTimeoutMs) { transport.write(packet) }
            ?: throw TransportException("Write timed out")
    }
}
