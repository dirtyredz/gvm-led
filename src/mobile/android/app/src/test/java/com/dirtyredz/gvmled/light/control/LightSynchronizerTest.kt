package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.connection.FakeLightTransport
import com.dirtyredz.gvmled.light.connection.FakeTransports
import com.dirtyredz.gvmled.light.connection.LightSession
import com.dirtyredz.gvmled.light.connection.LinkStatus
import com.dirtyredz.gvmled.light.connection.ReconnectBackoff
import com.dirtyredz.gvmled.light.connection.SimulatedLight
import com.dirtyredz.gvmled.light.connection.Vectors
import com.dirtyredz.gvmled.light.protocol.LegacyCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One real [LightSession] over a fake transport and a [SimulatedLight], reconnecting one second
 * after each generation ends (as the coordinator does), with a [LightSynchronizer] attached.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightSynchronizerTest {
    private val address = "94:C9:60:E5:D7:6F"
    private val amber = WantedState(power = true, mode = LightMode.Hsi, brightness = 50, hue = 7, saturation = 100, cct = 32)
    private val hsiSequence = listOf(
        LegacyCommand.MODE to 2, LegacyCommand.HUE to 7, LegacyCommand.SATURATION to 100,
        LegacyCommand.BRIGHTNESS to 50, LegacyCommand.POWER to 1,
    )

    private inner class Rig(scope: TestScope, initial: WantedRecord?, val light: SimulatedLight) {
        val transports = FakeTransports { scope.testScheduler.currentTime }.also { t -> t.configure = { light.attach(it) } }
        val session = LightSession(address, transports, { scope.testScheduler.currentTime }, { 0L })
        val wanted = MutableStateFlow(initial)
        var seeds = 0
        val sync = LightSynchronizer(
            address = address,
            connection = session.state,
            port = session.port,
            wanted = wanted,
            seed = { report, range ->
                seeds++
                WantedState.fromReport(report, range)?.let { WantedRecord(1, it) }?.also { wanted.value = it }
            },
            timing = SyncTiming(confirmTimeoutMs = 6_000, rounds = 3),
            backoff = ReconnectBackoff(baseMs = 1_000, maxMs = 30_000),
        )

        init {
            scope.backgroundScope.launch { sync.run() }
            scope.backgroundScope.launch {
                while (true) {
                    session.connectOnce(Mutex())
                    delay(1_000)
                }
            }
        }

        val transport: FakeLightTransport get() = transports.created.last()
        fun report(receivedAt: Long? = null) = light.report(transport, receivedAt)
    }

    private fun TestScope.rig(initial: WantedRecord?, light: SimulatedLight = SimulatedLight()) = Rig(this, initial, light)

    /** The original colour bug: light in CCT, wanted HSI, first transaction lost. */
    @Test fun colourRegressionADroppedTransactionIsReplayedModeFirstUntilAFreshReportMatches() = runTest {
        val light = SimulatedLight(mode = 1, cct = 32, hue = 0, saturation = 0).apply { dropSets = 5 }
        val rig = rig(WantedRecord(2, amber), light)
        runCurrent()
        advanceTimeBy(1_500) // round 1: five paced writes, all lost
        assertEquals(hsiSequence, light.written)
        assertEquals(ApplyStatus.Pending(2, 1), rig.sync.status.value)

        rig.report() // fresh, but still CCT: a mismatch retries the whole transaction at once
        advanceTimeBy(1_500)
        assertEquals(hsiSequence + hsiSequence, light.written)
        assertEquals(hsiSequence, light.applied)
        assertEquals(ApplyStatus.Pending(2, 2), rig.sync.status.value)

        rig.report()
        runCurrent()
        assertEquals(ApplyStatus.Confirmed(2), rig.sync.status.value)
        assertEquals(2, light.mode)

        advanceTimeBy(30_000)
        rig.report()
        runCurrent()
        assertEquals("confirmed means no corrective writes", 10, light.written.size)
    }

    @Test fun aQueuedReportReceivedBeforeTheFinalWriteNeverConfirms() = runTest {
        val rig = rig(WantedRecord(2, amber))
        runCurrent()
        advanceTimeBy(1_500)
        assertEquals(hsiSequence, rig.light.applied)
        rig.report(receivedAt = 500) // matching state, but stamped before the transaction finished
        advanceTimeBy(100)
        assertEquals(ApplyStatus.Pending(2, 1), rig.sync.status.value)
        rig.report()
        runCurrent()
        assertEquals(ApplyStatus.Confirmed(2), rig.sync.status.value)
    }

    @Test fun fullCircleHueReportConfirmsZeroHueWithoutRetry() = runTest {
        val zeroHue = amber.copy(hue = 0)
        val light = SimulatedLight(hue = 72, saturation = 100)
        val rig = rig(WantedRecord(2, zeroHue), light)
        runCurrent()
        advanceTimeBy(1_500)
        assertEquals(hsiSequence.map { if (it.first == LegacyCommand.HUE) it.first to 0 else it }, light.written)

        light.hue = 72 // the light reports 360 degrees for the commanded zero-degree colour
        rig.report()
        runCurrent()
        assertEquals(ApplyStatus.Confirmed(2), rig.sync.status.value)
        advanceTimeBy(6_500)
        assertEquals("confirmation prevents a retry", 5, light.written.size)
    }

    @Test fun aShortReplyNeverConfirmsAndThreeSilentRoundsEndInNotConfirmedThenReconnect() = runTest {
        val rig = rig(WantedRecord(2, amber))
        runCurrent()
        advanceTimeBy(1_500)
        rig.transport.notify(Vectors.SHORT_REPLY)
        advanceTimeBy(5_000)
        assertEquals(ApplyStatus.Pending(2, 1), rig.sync.status.value)
        advanceTimeBy(14_500) // rounds 2 and 3 time out too
        assertEquals(15, rig.light.written.size)
        assertTrue(rig.sync.status.value is ApplyStatus.NotConfirmed)
        assertEquals(1, rig.transports.created.size)

        advanceTimeBy(3_500) // capped backoff, then the generation is dropped and reconnected
        runCurrent()
        assertEquals(2, rig.transports.created.size)
        assertTrue(rig.transports.created.first().closed)
        advanceTimeBy(1_500)
        assertEquals("the new generation replays mode first", hsiSequence, rig.light.written.drop(15))
        rig.report()
        runCurrent()
        assertEquals(ApplyStatus.Confirmed(2), rig.sync.status.value)
    }

    @Test fun aNewerRevisionCancelsTheObsoleteWritesAndRestartsWithMode() = runTest {
        val rig = rig(WantedRecord(2, amber))
        runCurrent()
        advanceTimeBy(450) // mode and hue of revision 2 written
        rig.wanted.value = WantedRecord(3, amber.copy(hue = 48))
        advanceTimeBy(2_000)
        val written = rig.light.written
        val restart = written.indexOfLast { it == (LegacyCommand.MODE to 2) }
        assertTrue("revision 2 was cut short", restart in 1..4)
        assertEquals(listOf(LegacyCommand.MODE to 2, LegacyCommand.HUE to 48), written.subList(restart, restart + 2))
        assertTrue(written.subList(0, restart).none { it.first == LegacyCommand.POWER })
        rig.report()
        runCurrent()
        assertEquals(ApplyStatus.Confirmed(3), rig.sync.status.value)
    }

    @Test fun reconnectReplaysThePersistedIntentAndOldReportsCannotConfirmTheNewGeneration() = runTest {
        val rig = rig(WantedRecord(2, amber))
        runCurrent()
        advanceTimeBy(1_500)
        rig.report()
        runCurrent()
        assertEquals(ApplyStatus.Confirmed(2), rig.sync.status.value)

        rig.light.mode = 1 // between sessions the official app put it back in CCT
        rig.transport.disconnect("out of range")
        runCurrent()
        assertEquals(ApplyStatus.WaitingForLight(2), rig.sync.status.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(LinkStatus.Live, rig.session.state.value.status)
        assertEquals(ApplyStatus.Pending(2, 1), rig.sync.status.value)
        advanceTimeBy(1_500)
        assertEquals(hsiSequence + hsiSequence, rig.light.written)
        assertEquals(2, rig.light.mode)
        rig.report()
        runCurrent()
        assertEquals(ApplyStatus.Confirmed(2), rig.sync.status.value)
    }

    @Test fun withoutIntentTheFirstReportIsAdoptedAndNothingIsWritten() = runTest {
        val light = SimulatedLight(mode = 2, hue = 48, saturation = 100, brightness = 34)
        val rig = rig(null, light)
        runCurrent()
        assertEquals(ApplyStatus.NoSettings, rig.sync.status.value)
        rig.report()
        runCurrent()
        assertEquals(1, rig.seeds)
        assertEquals(WantedState(true, LightMode.Hsi, 34, cct = 32, hue = 48, saturation = 100, scene = 1, effectTime = 1), rig.wanted.value?.state)
        assertEquals(ApplyStatus.Confirmed(1), rig.sync.status.value)
        advanceTimeBy(10_000)
        assertTrue(light.written.isEmpty())
    }

    @Test fun intentOutsideTheCurrentRangeIsBlockedNotClamped() = runTest {
        val rig = rig(WantedRecord(4, WantedState(true, LightMode.Cct, 50, cct = 60)))
        runCurrent()
        advanceTimeBy(2_000)
        val status = rig.sync.status.value
        assertTrue("got $status", status is ApplyStatus.Blocked && status.reason.contains("3200-5600 K"))
        assertTrue(rig.light.written.isEmpty())
    }
}
