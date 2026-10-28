package com.dirtyredz.gvmled.light.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every write of one connection generation goes through here: hello, report acknowledgements and set
 * commands. One write at a time, at least [spacingMs] apart (the official app's 200 ms tick), each
 * bounded by [timeoutMs]. After [retire] the generation is over and every write is refused, so a
 * command port kept past its connection can never reach a newer link.
 */
class SessionWriter(
    private val transport: LightTransport,
    private val monotonicMillis: () -> Long,
    private val spacingMs: Long,
    private val timeoutMs: Long,
) {
    private val mutex = Mutex()
    private var lastWriteAt: Long? = null
    @Volatile private var retired = false

    val isRetired: Boolean get() = retired

    /** Writes one packet; returns the monotonic time its write completed. */
    suspend fun write(packet: ByteArray): Long = mutex.withLock {
        if (retired) throw TransportException("Connection ended")
        lastWriteAt?.let { previous ->
            val wait = previous + spacingMs - monotonicMillis()
            if (wait > 0) delay(wait)
        }
        if (retired) throw TransportException("Connection ended")
        lastWriteAt = monotonicMillis()
        withTimeoutOrNull(timeoutMs) { transport.write(packet) } ?: throw TransportException("Write timed out")
        monotonicMillis()
    }

    fun retire() {
        retired = true
    }
}
