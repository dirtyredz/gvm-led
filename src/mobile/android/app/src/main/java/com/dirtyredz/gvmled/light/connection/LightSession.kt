package com.dirtyredz.gvmled.light.connection

import com.dirtyredz.gvmled.data.model.BluetoothAddress
import com.dirtyredz.gvmled.light.protocol.FullStateReport
import com.dirtyredz.gvmled.light.protocol.HelloReply
import com.dirtyredz.gvmled.light.protocol.LegacyFrameDecoder
import com.dirtyredz.gvmled.light.protocol.LegacyMessage
import com.dirtyredz.gvmled.light.protocol.LegacyPacketEncoder
import com.dirtyredz.gvmled.light.protocol.SetCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A decoded message and the monotonic receipt time of the notification that carried it. */
private class Received(val message: LegacyMessage, val atMillis: Long)

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
 * While a generation is live it publishes a [LightCommandPort] in [port]; every write of the
 * generation (hello, acknowledgements, set commands) goes through one [SessionWriter].
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
    val address: String = BluetoothAddress.normalize(address)

    private val mutableState = MutableStateFlow(LightConnection(this.address))
    val state: StateFlow<LightConnection> = mutableState.asStateFlow()

    private val mutablePort = MutableStateFlow<LightCommandPort?>(null)

    /** Command access to the current live generation; null whenever no generation is live. */
    val port: StateFlow<LightCommandPort?> = mutablePort.asStateFlow()

    private val decoder = LegacyFrameDecoder()

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
        var writer: SessionWriter? = null
        var reachedLive = false
        try {
            val pending = ArrayDeque<Received>()
            val (link, hello) = establish.withLock {
                val created = transports.create(address).also { transport = it }
                val paced = SessionWriter(created, monotonicMillis, timing.writeSpacingMs, timing.writeTimeoutMs)
                writer = paced
                created to openLink(created, paced, pending)
            }
            reachedLive = true
            val paced = checkNotNull(writer)
            val live = Port(generation, hello, link, paced)
            mutableState.update { it.copy(status = LinkStatus.Live, hello = hello, helloGeneration = generation) }
            mutablePort.value = live
            log("$address live: type ${hello.deviceType}, ${hello.minCctKelvin}-${hello.maxCctKelvin} K")
            val ended = receive(link, paced, hello, generation, pending)
            return SessionOutcome(reachedLive = true, reason = live.dropReason ?: ended)
        } catch (failure: TransportException) {
            log("$address generation $generation failed: ${failure.message}")
            return SessionOutcome(reachedLive, failure.message ?: "Connection failed")
        } finally {
            writer?.retire()
            mutablePort.value = null
            transport?.close()
            decoder.reset()
        }
    }

    private suspend fun openLink(transport: LightTransport, writer: SessionWriter, pending: ArrayDeque<Received>): HelloReply {
        withTimeoutOrNull(timing.connectTimeoutMs) { transport.connect() }
            ?: throw TransportException("Connection timed out")
        withTimeoutOrNull(timing.subscribeTimeoutMs) { transport.subscribe() }
            ?: throw TransportException("Subscription timed out")
        mutableState.update { it.copy(status = LinkStatus.Handshaking) }
        repeat(timing.helloAttempts) {
            writer.write(LegacyPacketEncoder.hello())
            withTimeoutOrNull(timing.helloIntervalMs) { awaitHello(transport, pending) }?.let { return it }
        }
        throw TransportException("No valid hello reply after ${timing.helloAttempts} attempts")
    }

    /** Messages decoded after the hello in the same notification are kept for the live phase. */
    private suspend fun awaitHello(transport: LightTransport, pending: ArrayDeque<Received>): HelloReply {
        while (true) {
            val event = transport.events.receiveCatching().getOrNull() ?: throw TransportException("Link closed")
            when (event) {
                is TransportEvent.Disconnected -> throw TransportException(event.reason)
                is TransportEvent.Notification -> {
                    val messages = decoder.feed(event.bytes)
                    val index = messages.indexOfFirst { it is HelloReply }
                    if (index >= 0) {
                        messages.subList(index + 1, messages.size).forEach { pending.add(Received(it, event.receivedAtMillis)) }
                        return messages[index] as HelloReply
                    }
                }
            }
        }
    }

    private suspend fun receive(
        transport: LightTransport,
        writer: SessionWriter,
        hello: HelloReply,
        generation: Int,
        pending: ArrayDeque<Received>,
    ): String {
        while (pending.isNotEmpty()) handle(writer, hello, generation, pending.removeFirst())
        for (event in transport.events) {
            when (event) {
                is TransportEvent.Disconnected -> return event.reason
                is TransportEvent.Notification -> decoder.feed(event.bytes).forEach {
                    handle(writer, hello, generation, Received(it, event.receivedAtMillis))
                }
            }
        }
        return "Link closed"
    }

    /** Only full reports matching the hello identity change state; short replies never do. */
    private suspend fun handle(writer: SessionWriter, hello: HelloReply, generation: Int, received: Received) {
        val message = received.message
        if (message !is FullStateReport) return
        if (message.deviceId != hello.deviceId || message.deviceType != hello.deviceType) {
            log("$address ignored a report for device ${message.deviceId}/${message.deviceType}")
            return
        }
        mutableState.update {
            it.copy(
                report = message,
                reportGeneration = generation,
                reportAtMillis = wallMillis(),
                reportReceivedAtMillis = received.atMillis,
            )
        }
        try {
            writer.write(LegacyPacketEncoder.acknowledge(message))
        } catch (failure: TransportException) {
            log("$address acknowledgement failed: ${failure.message}")
        }
    }

    /** The live generation's command access; refused once the generation has ended. */
    private inner class Port(
        override val generation: Int,
        override val hello: HelloReply,
        private val transport: LightTransport,
        private val writer: SessionWriter,
    ) : LightCommandPort {
        override val address: String get() = this@LightSession.address

        @Volatile var dropReason: String? = null
            private set

        override suspend fun send(commands: List<SetCommand>): Long {
            var completedAt = monotonicMillis()
            for (command in commands) completedAt = writer.write(command.encode(hello.deviceId, hello.deviceType))
            return completedAt
        }

        /** Closing the transport ends the receive loop; connectOnce then reports [reason] as the outcome. */
        override fun reconnect(reason: String) {
            if (writer.isRetired) return
            dropReason = reason
            log("$address generation $generation dropped: $reason")
            writer.retire()
            transport.close()
        }
    }
}
