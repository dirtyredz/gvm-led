package com.dirtyredz.gvmled.light.ble

import com.dirtyredz.gvmled.light.connection.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** GATT operations that complete through a callback. */
enum class GattOperation { DiscoverServices, WriteDescriptor, WriteCharacteristic }

/**
 * Android allows one outstanding GATT operation per connection. This serializes them: each
 * operation starts only after the previous one's callback arrived (or it failed or timed out).
 * Completions are matched by kind, so a stray callback cannot finish a different operation.
 * [complete] and [failPending] may be called from the binder thread.
 */
class GattOperationQueue(private val timeoutMs: Long) {
    private class Pending(val operation: GattOperation, val result: CompletableDeferred<Int>)

    private val mutex = Mutex()
    private val lock = Any()
    private var pending: Pending? = null

    /** Runs [start] (which returns false if Android refused to begin) and returns the callback status. */
    suspend fun execute(operation: GattOperation, start: () -> Boolean): Int = mutex.withLock {
        val current = Pending(operation, CompletableDeferred())
        synchronized(lock) { pending = current }
        try {
            if (!start()) throw TransportException("$operation could not start")
            withTimeoutOrNull(timeoutMs) { current.result.await() }
                ?: throw TransportException("$operation timed out")
        } finally {
            synchronized(lock) { if (pending === current) pending = null }
        }
    }

    fun complete(operation: GattOperation, status: Int) {
        val current = synchronized(lock) { pending }
        if (current?.operation == operation) current.result.complete(status)
    }

    fun failPending(reason: String) {
        synchronized(lock) { pending }?.result?.completeExceptionally(TransportException(reason))
    }
}
