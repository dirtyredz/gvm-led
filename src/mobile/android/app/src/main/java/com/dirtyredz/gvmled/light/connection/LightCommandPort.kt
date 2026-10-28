package com.dirtyredz.gvmled.light.connection

import com.dirtyredz.gvmled.light.protocol.HelloReply
import com.dirtyredz.gvmled.light.protocol.SetCommand

/**
 * Command access to one live connection generation. It exists only while that generation is live
 * (`LightSession.port`); once the generation ends every call is refused, so a holder can never write
 * to a later connection by accident. Writes go through the session's [SessionWriter], interleaved
 * with acknowledgements and paced like them.
 */
interface LightCommandPort {
    val address: String
    val generation: Int

    /** The hello this generation was established with: device identity and CCT range. */
    val hello: HelloReply

    /**
     * Writes [commands] in order. Returns the monotonic time the last write completed: only a full
     * report received after it can confirm the transaction. Throws [TransportException] when the
     * generation has ended or a write fails.
     */
    suspend fun send(commands: List<SetCommand>): Long

    /** Ends this generation (the coordinator then reconnects with backoff). Idempotent. */
    fun reconnect(reason: String)
}
