package com.dirtyredz.gvmled.light.connection

import com.dirtyredz.gvmled.light.protocol.LegacyCommand
import com.dirtyredz.gvmled.light.protocol.SetCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionWriterTest {
    private val address = "94:C9:60:E5:D7:6F"

    private fun TestScope.transport() = FakeLightTransport(address, mutableListOf()) { testScheduler.currentTime }

    @Test fun writesAreSerializedAndPacedAndReturnTheirCompletionTime() = runTest {
        val transport = transport()
        val writer = SessionWriter(transport, { testScheduler.currentTime }, spacingMs = 200, timeoutMs = 2_000)
        val first = async { writer.write(byteArrayOf(1)) }
        val second = async { writer.write(byteArrayOf(2)) }
        val third = async { writer.write(byteArrayOf(3)) }
        advanceUntilIdle()
        assertEquals(listOf(0L, 200L, 400L), transport.writes.map { it.first })
        assertEquals(listOf(0L, 200L, 400L), listOf(first.await(), second.await(), third.await()))
    }

    @Test fun aRetiredWriterRefusesEveryWrite() = runTest {
        val writer = SessionWriter(transport(), { testScheduler.currentTime }, 200, 2_000)
        writer.retire()
        try {
            writer.write(byteArrayOf(1))
            fail("expected a refusal")
        } catch (refused: TransportException) {
            assertEquals("Connection ended", refused.message)
        }
    }

    @Test fun portExistsOnlyWhileLiveAndAnOldPortCannotReachTheNextGeneration() = runTest {
        val transports = FakeTransports { testScheduler.currentTime }
        val session = LightSession(address, transports, { testScheduler.currentTime }, { 0L })
        assertNull(session.port.value)
        val gate = CompletableDeferred<Unit>()
        transports.configure = { it.subscribeGate = gate }
        backgroundScope.launch { while (true) { session.connectOnce(Mutex()); kotlinx.coroutines.delay(1_000) } }
        runCurrent()
        assertNull("not live during the handshake", session.port.value)
        gate.complete(Unit)
        runCurrent()
        val first = session.port.value!!
        assertEquals(1, first.generation)
        assertEquals(0x33, first.hello.deviceType)

        val sentAt = backgroundScope.async { first.send(listOf(SetCommand(LegacyCommand.MODE, 2), SetCommand(LegacyCommand.HUE, 7))) }
        advanceTimeBy(1_000)
        assertEquals(400L, sentAt.await()) // after hello at 0: paced at 200 and 400
        val packets = transports.latest(address).writes.drop(1).map { it.second.toList() }
        assertEquals(listOf(SetCommand(LegacyCommand.MODE, 2).encode(0, 0x33).toList(), SetCommand(LegacyCommand.HUE, 7).encode(0, 0x33).toList()), packets)

        transports.latest(address).disconnect("gone")
        runCurrent()
        assertNull(session.port.value)
        try {
            first.send(listOf(SetCommand(LegacyCommand.POWER, 1)))
            fail("a retired port must refuse")
        } catch (_: TransportException) {
        }
        advanceTimeBy(1_100)
        val second = session.port.value!!
        assertEquals(2, second.generation)
        assertTrue(transports.created.first().writes.none { it.second.toList() == SetCommand(LegacyCommand.POWER, 1).encode(0, 0x33).toList() })
    }

    @Test fun reconnectEndsTheGenerationWithItsReason() = runTest {
        val transports = FakeTransports { testScheduler.currentTime }
        val session = LightSession(address, transports, { testScheduler.currentTime }, { 0L })
        val outcome = async { session.connectOnce(Mutex()) }
        runCurrent()
        session.port.value!!.reconnect("Not confirmed")
        runCurrent()
        assertEquals("Not confirmed", outcome.await().reason)
        assertTrue(outcome.await().reachedLive)
        assertTrue(transports.latest(address).closed)
        assertNull(session.port.value)
    }
}
