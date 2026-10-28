package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.light.connection.LightConnection
import com.dirtyredz.gvmled.light.connection.LinkStatus
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.light.control.ApplyStatus
import com.dirtyredz.gvmled.light.control.LightMode
import com.dirtyredz.gvmled.light.control.WantedRecord
import com.dirtyredz.gvmled.light.control.WantedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LightsRepositoryTest {
    private val a = "94:C9:60:E5:D7:6F"
    private val b = "94:C9:60:EB:67:E6"
    private val c = "94:C9:60:AA:BB:CC"

    private fun catalog(vararg lights: Pair<String, String>) =
        LightCatalog(lights.map { KnownLight(it.first, it.second) }, emptyList())

    private fun repo(
        connections: StateFlow<List<LightConnection>>,
        catalog: StateFlow<LightCatalog>,
        wanted: StateFlow<Map<String, WantedRecord>> = MutableStateFlow(emptyMap()),
        statuses: StateFlow<Map<String, ApplyStatus>> = MutableStateFlow(emptyMap()),
    ) = LightsRepository(connections, catalog, wanted, statuses)

    @Test fun entriesCarryEachLightsOwnWantedStateAndApplyStatus() = runTest {
        val record = WantedRecord(3, WantedState(power = true, mode = LightMode.Hsi, brightness = 50, hue = 7, saturation = 100))
        val repository = repo(
            MutableStateFlow(listOf(LightConnection(a), LightConnection(b))),
            MutableStateFlow(catalog(a to "Key", b to "Fill")),
            MutableStateFlow(mapOf(a to record)),
            MutableStateFlow(mapOf(a to ApplyStatus.Confirmed(3), b to ApplyStatus.NoSettings)),
        )
        val entries = repository.lights.first()
        assertEquals(record, entries[0].wanted)
        assertEquals(ApplyStatus.Confirmed(3), entries[0].apply)
        assertEquals(null, entries[1].wanted)
        assertEquals(ApplyStatus.NoSettings, entries[1].apply)
    }

    @Test fun entriesFollowCatalogOrderWithCatalogNames() = runTest {
        val connections = MutableStateFlow(listOf(LightConnection(a, LinkStatus.Live), LightConnection(c, LinkStatus.Searching)))
        val repository = repo(connections, MutableStateFlow(catalog(c to "Back", a to "Key")))
        val entries = repository.lights.first()
        assertEquals(listOf(c, a), entries.map { it.connection.address })
        assertEquals(listOf("Back", "Key"), entries.map { it.name })
        assertEquals(LinkStatus.Searching, entries[0].connection.status)
        assertEquals(LinkStatus.Live, entries[1].connection.status)
    }

    @Test fun catalogLightWithoutAConnectionShowsIdle() = runTest {
        val connections = MutableStateFlow(listOf(LightConnection(a, LinkStatus.Live)))
        val repository = repo(connections, MutableStateFlow(catalog(a to "Key", b to "Fill")))
        val entries = repository.lights.first()
        assertEquals(LightConnection(b), entries[1].connection)
        assertEquals(LinkStatus.Idle, entries[1].connection.status)
    }

    @Test fun renamedCatalogLightUpdatesTheName() = runTest {
        val known = MutableStateFlow(catalog(a to "Key"))
        val repository = repo(MutableStateFlow(listOf(LightConnection(a))), known)
        assertEquals("Key", repository.lights.first().single().name)
        known.value = catalog(a to "Hero")
        assertEquals("Hero", repository.lights.first().single().name)
    }

    @Test fun connectionsForUnknownAddressesAreNotShown() = runTest {
        val connections = MutableStateFlow(listOf(LightConnection(a), LightConnection(b), LightConnection(c, LinkStatus.Live)))
        val repository = repo(connections, MutableStateFlow(catalog(a to "Key")))
        assertEquals(listOf(a), repository.lights.first().map { it.connection.address })
    }
}
