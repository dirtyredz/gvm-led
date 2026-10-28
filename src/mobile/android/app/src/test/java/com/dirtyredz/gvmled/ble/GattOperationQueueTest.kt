package com.dirtyredz.gvmled.ble

import com.dirtyredz.gvmled.connection.TransportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattOperationQueueTest {
    @Test fun operationsStartOnlyAfterThePreviousCallback() = runTest {
        val queue = GattOperationQueue(timeoutMs = 5_000)
        val started = mutableListOf<GattOperation>()
        val first = async { queue.execute(GattOperation.WriteDescriptor) { started += GattOperation.WriteDescriptor; true } }
        val second = async { queue.execute(GattOperation.WriteCharacteristic) { started += GattOperation.WriteCharacteristic; true } }
        runCurrent()
        assertEquals(listOf(GattOperation.WriteDescriptor), started)

        queue.complete(GattOperation.WriteDescriptor, 0)
        runCurrent()
        assertEquals(0, first.await())
        assertEquals(listOf(GattOperation.WriteDescriptor, GattOperation.WriteCharacteristic), started)
        queue.complete(GattOperation.WriteCharacteristic, 0)
        assertEquals(0, second.await())
    }

    @Test fun aStrayCallbackOfAnotherKindDoesNotComplete() = runTest {
        val queue = GattOperationQueue(timeoutMs = 5_000)
        val write = async { queue.execute(GattOperation.WriteCharacteristic) { true } }
        runCurrent()
        queue.complete(GattOperation.WriteDescriptor, 0)
        runCurrent()
        assertFalse(write.isCompleted)
        queue.complete(GattOperation.WriteCharacteristic, 133)
        assertEquals(133, write.await())
    }

    @Test fun refusedStartAndTimeoutFailWithoutBlockingTheQueue() = runTest {
        val queue = GattOperationQueue(timeoutMs = 5_000)
        val refused = runCatching { queue.execute(GattOperation.DiscoverServices) { false } }
        assertTrue(refused.exceptionOrNull() is TransportException)

        val timedOut = async { runCatching { queue.execute(GattOperation.DiscoverServices) { true } } }
        advanceTimeBy(5_001)
        assertTrue(timedOut.await().exceptionOrNull() is TransportException)

        val next = async { queue.execute(GattOperation.WriteCharacteristic) { true } }
        runCurrent()
        queue.complete(GattOperation.WriteCharacteristic, 0)
        assertEquals(0, next.await())
    }

    @Test fun disconnectFailsThePendingOperation() = runTest {
        val queue = GattOperationQueue(timeoutMs = 5_000)
        val write = async { runCatching { queue.execute(GattOperation.WriteCharacteristic) { true } } }
        runCurrent()
        queue.failPending("Disconnected (GATT status 8)")
        assertEquals("Disconnected (GATT status 8)", write.await().exceptionOrNull()?.message)
    }
}
