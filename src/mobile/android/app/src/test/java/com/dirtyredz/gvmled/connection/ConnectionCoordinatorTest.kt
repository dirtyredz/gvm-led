package com.dirtyredz.gvmled.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {
    private val light1 = "94:C9:60:E5:D7:6F"
    private val light2 = "94:C9:60:EB:67:E6"

    private class Rig(
        val coordinator: ConnectionCoordinator,
        val scanner: FakeScanner,
        val transports: FakeTransports,
        val radio: MutableStateFlow<RadioState>,
    )

    private fun TestScope.rig(radioState: RadioState = RadioState.Ready): Rig {
        val scanner = FakeScanner()
        val transports = FakeTransports { testScheduler.currentTime }
        val radio = MutableStateFlow(radioState)
        val coordinator = ConnectionCoordinator(
            addresses = listOf(light1, light2),
            scanner = scanner,
            transports = transports,
            radio = radio,
            scope = backgroundScope,
            monotonicMillis = { testScheduler.currentTime },
            wallMillis = { 0L },
        )
        return Rig(coordinator, scanner, transports, radio)
    }

    private fun Rig.statuses() = coordinator.lights.value.map { it.status }

    private suspend fun Rig.advertise(address: String, name: String? = "BT_LED") =
        scanner.advertisements.emit(Advertisement(address, name, -70, listOf("0000180f-0000-1000-8000-00805f9b34fb")))

    @Test fun nothingScansOrConnectsUntilBluetoothIsReady() = runTest {
        val rig = rig(RadioState.PermissionRequired)
        rig.coordinator.start()
        runCurrent()
        assertEquals(0, rig.scanner.activeScans)
        assertEquals(listOf(LinkStatus.Idle, LinkStatus.Idle), rig.statuses())

        rig.radio.value = RadioState.Ready
        runCurrent()
        assertEquals(1, rig.scanner.activeScans)
        assertEquals(listOf(LinkStatus.Searching, LinkStatus.Searching), rig.statuses())
        assertTrue(rig.transports.created.isEmpty())
    }

    @Test fun lightsConnectOneAfterAnotherThenBothStayLive() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        rig.transports.configure = { if (it.address == light1) it.connectGate = gate }
        rig.coordinator.start()
        runCurrent()
        rig.advertise(light1)
        rig.advertise(light2)
        runCurrent()

        assertEquals(listOf(light1), rig.transports.created.map { it.address })

        gate.complete(Unit)
        runCurrent()
        val log = rig.transports.log
        assertTrue(log.indexOf("$light1 write") < log.indexOf("$light2 create"))
        assertEquals(listOf(LinkStatus.Live, LinkStatus.Live), rig.statuses())
        assertEquals(0, rig.scanner.activeScans) // nobody is searching any more

        rig.transports.latest(light1).notify(Vectors.REPORT_1)
        rig.transports.latest(light2).notify(Vectors.REPORT_2)
        advanceTimeBy(500)
        assertEquals(listOf(7, 34), rig.coordinator.lights.value.map { it.report?.brightness })
    }

    @Test fun lightsAreIdentifiedByAddressAndUnknownBtLedIsNeverConnected() = runTest {
        val rig = rig()
        rig.coordinator.start()
        runCurrent()
        rig.advertise("11:22:33:44:55:66")
        rig.advertise("AA:BB:CC:DD:EE:FF", name = "Govee")
        rig.advertise(light2.lowercase(), name = null)
        runCurrent()

        assertEquals(setOf("11:22:33:44:55:66"), rig.coordinator.otherLights.value)
        assertEquals(listOf(light2), rig.transports.created.map { it.address })
    }

    @Test fun disconnectClosesTheOldLinkAndReconnectsOnlyAfterAFreshSighting() = runTest {
        val rig = rig()
        rig.coordinator.start()
        runCurrent()
        rig.advertise(light1)
        runCurrent()
        val first = rig.transports.latest(light1)
        first.notify(Vectors.REPORT_1)
        advanceTimeBy(500)

        first.disconnect("Disconnected (GATT status 8)")
        runCurrent()
        assertTrue(first.closed)
        assertEquals(LinkStatus.Waiting, rig.statuses()[0])
        assertFalse(rig.coordinator.lights.value[0].reportIsCurrent)

        advanceTimeBy(1_001)
        assertEquals(LinkStatus.Searching, rig.statuses()[0])
        assertEquals(1, rig.transports.created.size) // no blind reconnect

        rig.advertise(light1)
        runCurrent()
        val second = rig.transports.latest(light1)
        assertTrue(second !== first)
        val log = rig.transports.log
        assertTrue(log.indexOf("$light1 close") < log.lastIndexOf("$light1 create"))
        val state = rig.coordinator.lights.value[0]
        assertEquals(LinkStatus.Live, state.status)
        assertEquals(2, state.generation)
        assertFalse(state.reportIsCurrent) // the old generation's report is not current
    }

    @Test fun failuresBackOffBeforeSearchingAgain() = runTest {
        val rig = rig()
        rig.transports.configure = { it.connectFailure = "Disconnected (GATT status 133)" }
        rig.coordinator.start()
        runCurrent()
        rig.advertise(light1)
        runCurrent()
        assertEquals(LinkStatus.Waiting, rig.statuses()[0])
        assertEquals("Disconnected (GATT status 133)", rig.coordinator.lights.value[0].lastError)

        advanceTimeBy(1_999)
        assertEquals(LinkStatus.Waiting, rig.statuses()[0])
        advanceTimeBy(2)
        assertEquals(LinkStatus.Searching, rig.statuses()[0])
    }

    @Test fun anUnexpectedPlatformErrorBecomesARetryNotACrash() = runTest {
        val rig = rig()
        rig.transports.configure = { throw IllegalStateException("No Bluetooth adapter") }
        rig.coordinator.start()
        runCurrent()
        rig.advertise(light1)
        runCurrent()

        assertEquals(LinkStatus.Waiting, rig.statuses()[0])
        assertEquals("No Bluetooth adapter", rig.coordinator.lights.value[0].lastError)
        advanceTimeBy(2_001)
        assertEquals(LinkStatus.Searching, rig.statuses()[0])
    }

    @Test fun stopClosesEveryLinkAndStartReconnects() = runTest {
        val rig = rig()
        rig.coordinator.start()
        runCurrent()
        rig.advertise(light1)
        rig.advertise(light2)
        runCurrent()
        assertEquals(listOf(LinkStatus.Live, LinkStatus.Live), rig.statuses())

        rig.coordinator.stop()
        runCurrent()
        assertTrue(rig.transports.created.all { it.closed })
        assertEquals(listOf(LinkStatus.Idle, LinkStatus.Idle), rig.statuses())
        assertEquals(0, rig.scanner.activeScans)

        rig.coordinator.start()
        runCurrent()
        assertEquals(listOf(LinkStatus.Searching, LinkStatus.Searching), rig.statuses())
        assertEquals(1, rig.scanner.activeScans)
    }

    @Test fun bluetoothTurningOffClosesLinks() = runTest {
        val rig = rig()
        rig.coordinator.start()
        runCurrent()
        rig.advertise(light1)
        runCurrent()
        rig.radio.value = RadioState.Off
        runCurrent()

        assertTrue(rig.transports.latest(light1).closed)
        assertEquals(listOf(LinkStatus.Idle, LinkStatus.Idle), rig.statuses())
    }
}
