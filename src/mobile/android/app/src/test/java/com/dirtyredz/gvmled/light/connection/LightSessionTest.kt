package com.dirtyredz.gvmled.light.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LightSessionTest {
    private val address = "94:C9:60:E5:D7:6F"

    private fun TestScope.session(transports: FakeTransports) =
        LightSession(address, transports, { testScheduler.currentTime }, { 1_000L })

    private fun TestScope.transports() = FakeTransports { testScheduler.currentTime }

    @Test fun subscriptionCompletesBeforeHelloIsSent() = runTest {
        val transports = transports()
        val gate = CompletableDeferred<Unit>()
        transports.configure = { it.subscribeGate = gate }
        val session = session(transports)
        backgroundScope.async { session.connectOnce(Mutex()) }
        runCurrent()

        assertEquals(listOf("$address create", "$address connect", "$address subscribe"), transports.log)
        assertTrue(transports.latest(address).writes.isEmpty())
        assertEquals(LinkStatus.Connecting, session.state.value.status)

        gate.complete(Unit)
        runCurrent()
        assertEquals(
            listOf("$address subscribed", "$address write"),
            transports.log.drop(3).take(2),
        )
        assertArrayEquals(Vectors.HELLO_REQUEST, transports.latest(address).writes.first().second)
        assertEquals(LinkStatus.Live, session.state.value.status)
        assertEquals(0x33, session.state.value.hello?.deviceType)
        assertEquals(3200, session.state.value.hello?.minCctKelvin)
        assertEquals(5600, session.state.value.hello?.maxCctKelvin)
    }

    @Test fun helloRepeatsEverySecondWithinABoundedHandshake() = runTest {
        val transports = transports()
        transports.configure = { it.answerHello = false }
        val session = session(transports)
        val outcome = async { session.connectOnce(Mutex()) }
        advanceUntilIdle()

        val writes = transports.latest(address).writes
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L), writes.map { it.first })
        writes.forEach { assertArrayEquals(Vectors.HELLO_REQUEST, it.second) }
        assertFalse(outcome.await().reachedLive)
        assertTrue(outcome.await().reason.contains("hello"))
        assertTrue(transports.latest(address).closed)
    }

    @Test fun reportsUpdateStateAndArePacedAcknowledgements() = runTest {
        val transports = transports()
        val session = session(transports)
        backgroundScope.async { session.connectOnce(Mutex()) }
        runCurrent()
        val transport = transports.latest(address)
        transport.notify(Vectors.REPORT_1)
        advanceTimeBy(500)

        val state = session.state.value
        assertEquals(LinkStatus.Live, state.status)
        assertTrue(state.reportIsCurrent)
        val report = state.report!!
        assertEquals(listOf(1, 7, 71, 100, 2), listOf(report.power, report.brightness, report.hue, report.saturation, report.mode))
        assertEquals(1_000L, state.reportAtMillis)
        assertArrayEquals(Vectors.ACK_1, transport.writes[1].second)
        assertTrue(transport.writes[1].first - transport.writes[0].first >= 200)

        transport.notify(Vectors.REPORT_2)
        advanceTimeBy(500)
        assertEquals(34, session.state.value.report?.brightness)
        assertEquals(3, transport.writes.size)
    }

    @Test fun reportCoalescedWithHelloAndFragmentedReportsAreDecoded() = runTest {
        val transports = transports()
        transports.configure = { it.answerHello = false }
        val session = session(transports)
        backgroundScope.async { session.connectOnce(Mutex()) }
        runCurrent()
        val transport = transports.latest(address)
        transport.notify(Vectors.HELLO_REPLY + Vectors.REPORT_1)
        advanceTimeBy(500)
        assertEquals(7, session.state.value.report?.brightness)

        transport.notify(Vectors.REPORT_2.copyOfRange(0, 6))
        runCurrent()
        assertEquals(7, session.state.value.report?.brightness)
        transport.notify(Vectors.REPORT_2.copyOfRange(6, Vectors.REPORT_2.size))
        advanceTimeBy(500)
        assertEquals(34, session.state.value.report?.brightness)
    }

    @Test fun corruptShortAndForeignMessagesNeverChangeState() = runTest {
        val transports = transports()
        val session = session(transports)
        backgroundScope.async { session.connectOnce(Mutex()) }
        runCurrent()
        val transport = transports.latest(address)
        val corrupt = Vectors.REPORT_1.copyOf().also { it[8] = 0x08 }
        val foreignType = Vectors.framed("4C 54 0E 00 30 03 01 0B 07 2C 47 64 02 01 01")
        transport.notify(corrupt)
        transport.notify(Vectors.SHORT_REPLY)
        transport.notify(foreignType)
        advanceTimeBy(1_000)

        assertNull(session.state.value.report)
        assertEquals(1, transport.writes.size) // hello only, no acknowledgement
        assertEquals(LinkStatus.Live, session.state.value.status)
    }

    @Test fun disconnectEndsTheGenerationAndClosesTheTransport() = runTest {
        val transports = transports()
        val session = session(transports)
        val outcome = async { session.connectOnce(Mutex()) }
        runCurrent()
        val transport = transports.latest(address)
        transport.notify(Vectors.REPORT_1)
        advanceTimeBy(500)
        transport.disconnect("Disconnected (GATT status 8)")
        advanceUntilIdle()

        assertEquals(SessionOutcome(true, "Disconnected (GATT status 8)"), outcome.await())
        assertTrue(transport.closed)
        session.markWaiting("Disconnected (GATT status 8)")
        assertFalse(session.state.value.reportIsCurrent)
        assertEquals(7, session.state.value.report?.brightness) // kept, shown as stale
    }

    @Test fun connectTimeoutClosesTheTransport() = runTest {
        val transports = transports()
        transports.configure = { it.connectGate = CompletableDeferred() }
        val session = session(transports)
        val outcome = async { session.connectOnce(Mutex()) }
        advanceTimeBy(14_999)
        assertFalse(transports.latest(address).closed)
        advanceUntilIdle()

        assertEquals(SessionOutcome(false, "Connection timed out"), outcome.await())
        assertTrue(transports.latest(address).closed)
    }

    @Test fun failedConnectReportsTheReason() = runTest {
        val transports = transports()
        transports.configure = { it.connectFailure = "Legacy BT_LED service not found" }
        val outcome = session(transports).connectOnce(Mutex())

        assertEquals(SessionOutcome(false, "Legacy BT_LED service not found"), outcome)
        assertTrue(transports.latest(address).closed)
    }
}
