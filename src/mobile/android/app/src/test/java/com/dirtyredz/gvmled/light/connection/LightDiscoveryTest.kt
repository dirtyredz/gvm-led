package com.dirtyredz.gvmled.light.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LightDiscoveryTest {
    private val known = "94:C9:60:E5:D7:6F"
    private val unknownA = "11:22:33:44:55:66"
    private val unknownB = "AA:BB:CC:DD:EE:01"

    private class Rig(val discovery: LightDiscovery, val scanner: FakeScanner, val demand: MutableStateFlow<Boolean>)

    private fun TestScope.rig(): Rig {
        val scanner = FakeScanner()
        val discovery = LightDiscovery(scanner, { testScheduler.currentTime }, candidateTtlMs = 30_000, findWindowMs = 20_000)
        discovery.setKnown(listOf(known))
        val demand = MutableStateFlow(false)
        backgroundScope.launch { discovery.run(demand) }
        return Rig(discovery, scanner, demand)
    }

    private suspend fun Rig.advertise(address: String, name: String? = "BT_LED") =
        scanner.advertisements.emit(Advertisement(address, name, -60))

    @Test fun findIsBoundedAndSharesTheOneScanWithReconnectDemand() = runTest {
        val rig = rig()
        runCurrent()
        assertEquals(0, rig.scanner.activeScans)

        rig.discovery.find()
        runCurrent()
        assertTrue(rig.discovery.finding.value)
        assertEquals(1, rig.scanner.activeScans)

        rig.demand.value = true // a light starts searching during the window: still one scan
        runCurrent()
        assertEquals(1, rig.scanner.activeScans)
        rig.demand.value = false
        runCurrent()
        assertEquals(1, rig.scanner.activeScans)

        advanceTimeBy(20_001)
        assertFalse(rig.discovery.finding.value)
        assertEquals(0, rig.scanner.activeScans)
    }

    @Test fun candidatesAreUnknownBtLedByAddressAndExpireWhenNotSeen() = runTest {
        val rig = rig()
        rig.discovery.find()
        runCurrent()
        rig.advertise(unknownA)
        rig.advertise("AA:BB:CC:DD:EE:FF", name = "Govee") // not a BT_LED
        rig.advertise(known.lowercase(), name = null) // a known light is a sighting, not a candidate
        advanceTimeBy(10_000)
        rig.advertise(unknownB)
        runCurrent()

        assertEquals(listOf(unknownB, unknownA), rig.discovery.candidates.value.map { it.address })
        assertEquals(10_000L, rig.discovery.candidates.value.first().lastSeenMillis)

        advanceTimeBy(26_000) // unknownA last seen 36 s ago, unknownB 26 s ago
        assertEquals(listOf(unknownB), rig.discovery.candidates.value.map { it.address })
    }

    @Test fun enrollingACandidateRemovesItAndLaterSightingsCountForItsSession() = runTest {
        val rig = rig()
        rig.discovery.find()
        runCurrent()
        rig.advertise(unknownA)
        runCurrent()
        rig.discovery.setKnown(listOf(known, unknownA))
        assertTrue(rig.discovery.candidates.value.isEmpty())

        var sighted = false
        backgroundScope.launch { rig.discovery.awaitSighting(unknownA); sighted = true }
        runCurrent()
        assertFalse(sighted) // the earlier candidate sighting is not reused as a fresh one
        rig.advertise(unknownA)
        runCurrent()
        assertTrue(sighted)
    }

    @Test fun resetForgetsEverythingSeen() = runTest {
        val rig = rig()
        rig.discovery.find()
        runCurrent()
        rig.advertise(unknownA)
        runCurrent()
        rig.discovery.reset()
        assertTrue(rig.discovery.candidates.value.isEmpty())
        assertFalse(rig.discovery.finding.value)
    }
}
