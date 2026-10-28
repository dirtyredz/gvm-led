package com.dirtyredz.gvmled.connection

/** Wait before searching again: short after a live link ended, doubling (capped) after failures. */
class ReconnectBackoff(private val baseMs: Long = 1_000, private val maxMs: Long = 30_000) {
    fun delayFor(consecutiveFailures: Int): Long =
        if (consecutiveFailures <= 0) baseMs
        else (baseMs shl (consecutiveFailures.coerceAtMost(16))).coerceAtMost(maxMs)
}
