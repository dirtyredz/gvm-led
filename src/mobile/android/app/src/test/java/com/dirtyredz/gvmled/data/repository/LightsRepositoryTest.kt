package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.connection.LightConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LightsRepositoryTest {
    private class FakeNames : LightNameStore {
        override val names = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun rename(address: String, name: String?) {
            names.value = if (name == null) names.value - address else names.value + (address to name)
        }
    }

    private val light1 = "94:C9:60:E5:D7:6F"
    private val light2 = "94:C9:60:EB:67:E6"
    private val connections: StateFlow<List<LightConnection>> =
        MutableStateFlow(listOf(LightConnection(light1), LightConnection(light2)))

    @Test fun defaultNamesFollowConfiguredOrderAndRenamingIsPerAddress() = runTest {
        val names = FakeNames()
        val repository = LightsRepository(connections, MutableStateFlow(emptySet()), names)
        assertEquals(listOf("Light 1", "Light 2"), repository.lights.first().map { it.name })

        repository.rename(light2, "  Key light  ")
        val renamed = repository.lights.first()
        assertEquals(listOf("Light 1", "Key light"), renamed.map { it.name })
        assertEquals(listOf(false, true), renamed.map { it.isCustomName })

        repository.rename(light2, "   ")
        assertEquals(listOf("Light 1", "Light 2"), repository.lights.first().map { it.name })
    }

    @Test fun namesAreCapped() = runTest {
        val names = FakeNames()
        val repository = LightsRepository(connections, MutableStateFlow(emptySet()), names)
        repository.rename(light1, "x".repeat(40))
        assertEquals(LightsRepository.MAX_NAME_LENGTH, names.names.value.getValue(light1).length)
    }
}
