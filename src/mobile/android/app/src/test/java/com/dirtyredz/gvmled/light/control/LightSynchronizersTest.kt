package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.connection.Advertisement
import com.dirtyredz.gvmled.light.connection.ConnectionCoordinator
import com.dirtyredz.gvmled.light.connection.FakeScanner
import com.dirtyredz.gvmled.light.connection.FakeTransports
import com.dirtyredz.gvmled.light.connection.LightDiscovery
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.light.connection.SimulatedLight
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pool over a real coordinator: one synchronizer per light, failures independent. */
@OptIn(ExperimentalCoroutinesApi::class)
class LightSynchronizersTest {
    private val key = "94:C9:60:E5:D7:6F"
    private val fill = "94:C9:60:EB:67:E6"
    private val back = "94:C9:60:AA:BB:CC"
    private val amber = WantedState(power = true, mode = LightMode.Hsi, brightness = 50, hue = 7, saturation = 100)

    private class Rig(
        val coordinator: ConnectionCoordinator,
        val scanner: FakeScanner,
        val transports: FakeTransports,
        val pool: LightSynchronizers,
        val lights: Map<String, SimulatedLight>,
        val wanted: MutableStateFlow<Map<String, WantedRecord>>,
    )

    private fun TestScope.rig(addresses: List<String>, failing: Set<String>): Rig {
        val scanner = FakeScanner()
        val transports = FakeTransports { testScheduler.currentTime }
        val lights = addresses.associateWith { SimulatedLight() }
        transports.configure = { transport ->
            lights.getValue(transport.address).attach(transport)
            if (transport.address in failing) transport.connectFailure = "Out of range"
        }
        val coordinator = ConnectionCoordinator(
            addresses = MutableStateFlow(addresses),
            discovery = LightDiscovery(scanner, { testScheduler.currentTime }),
            transports = transports,
            radio = MutableStateFlow(RadioState.Ready),
            scope = backgroundScope,
            monotonicMillis = { testScheduler.currentTime },
            wallMillis = { 0L },
        )
        val wanted = MutableStateFlow(addresses.associateWith { WantedRecord(2, amber) })
        val pool = LightSynchronizers(coordinator.activeSessions, wanted, { _, _, _ -> null }, backgroundScope)
        return Rig(coordinator, scanner, transports, pool, lights, wanted)
    }

    private suspend fun Rig.advertiseAll() = lights.keys.forEach { scanner.advertisements.emit(Advertisement(it, "BT_LED", -70)) }

    @Test fun oneUnreachableMemberNeverBlocksTheOthersFromConfirming() = runTest {
        val rig = rig(listOf(key, fill, back), failing = setOf(fill))
        rig.coordinator.start()
        runCurrent()
        rig.advertiseAll()
        advanceTimeBy(3_000)
        for (address in listOf(key, back)) rig.lights.getValue(address).report(rig.transports.latest(address))
        runCurrent()

        val statuses = rig.pool.statuses.value
        assertEquals(ApplyStatus.Confirmed(2), statuses[key])
        assertEquals(ApplyStatus.Confirmed(2), statuses[back])
        assertEquals(ApplyStatus.WaitingForLight(2), statuses[fill])
        assertTrue(rig.lights.getValue(fill).written.isEmpty())
    }

    @Test fun backgroundReleasesLinksAndForegroundReplaysEveryLightWithoutDuplicates() = runTest {
        val rig = rig(listOf(key, fill), failing = emptySet())
        rig.coordinator.start()
        runCurrent()
        rig.advertiseAll()
        advanceTimeBy(3_000)
        rig.lights.forEach { (address, light) -> light.report(rig.transports.latest(address)) }
        runCurrent()
        assertTrue(rig.pool.statuses.value.values.all { it == ApplyStatus.Confirmed(2) })

        rig.coordinator.stop()
        runCurrent()
        assertEquals(setOf(ApplyStatus.WaitingForLight(2)), rig.pool.statuses.value.values.toSet())
        rig.coordinator.start()
        rig.coordinator.start() // a duplicate resume
        runCurrent()
        rig.advertiseAll()
        advanceTimeBy(3_000)
        rig.lights.values.forEach { light -> assertEquals("one full replay per light", 10, light.written.size) }
        assertEquals(4, rig.transports.created.size)
    }
}
