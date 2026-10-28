package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.data.local.LightCatalogCodec
import com.dirtyredz.gvmled.data.local.StoredCatalog
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LightCatalogRepositoryTest {
    private val a = "94:C9:60:E5:D7:6F"
    private val b = "94:C9:60:EB:67:E6"
    private val c = "94:C9:60:AA:BB:CC"

    private class FakeStore(var stored: StoredCatalog = StoredCatalog(null, 0, null, emptyMap())) : LightCatalogStore {
        var reads = 0
        var writes = 0
        var failWrites = false
        override fun read(): StoredCatalog {
            reads++
            return stored
        }
        override fun write(catalog: LightCatalog): Boolean {
            if (failWrites) return false
            writes++
            stored = StoredCatalog(LightCatalogCodec.encode(catalog), 2, null, emptyMap())
            return true
        }
    }

    private fun TestScope.repo(store: FakeStore): LightCatalogRepository {
        var next = 0
        return LightCatalogRepository(store, backgroundScope, UnconfinedTestDispatcher(testScheduler)) { "g${++next}" }
    }

    /** Seeded lights a and b, plus c "Back". */
    private suspend fun LightCatalogRepository.withThree(): LightCatalogRepository {
        addLight(c, "Back")
        return this
    }

    private fun assertRejected(result: CatalogResult) = assertTrue("expected Rejected, got $result", result is CatalogResult.Rejected)

    @Test fun firstRunSeedsAndWritesOnce() = runTest {
        val store = FakeStore()
        val repository = repo(store)
        assertEquals(LightCatalogCodec.seeded(), repository.catalog.value)
        assertEquals(1, store.writes)
        assertNull(repository.problem.value)
    }

    @Test fun initialReadAndMigrationAreAsynchronousAndKeepVersionOneCustomNames() = runTest {
        val store = FakeStore(
            StoredCatalog(
                catalogJson = null,
                legacyVersion = 1,
                legacyAddresses = "$a,$b",
                legacyNames = mapOf(a to "Custom Key"),
            ),
        )

        val repository = LightCatalogRepository(store, backgroundScope, StandardTestDispatcher(testScheduler))
        assertEquals(0, store.reads)
        assertEquals(0, store.writes)

        runCurrent()
        assertEquals(1, store.reads)
        assertEquals(1, store.writes)
        assertEquals(listOf("Custom Key", "Light 2"), repository.catalog.value.lights.map { it.name })
    }

    @Test fun restartSeesTheSameCatalogWithoutRewriting() = runTest {
        val store = FakeStore()
        val first = repo(store).withThree()
        first.createGroup("Key and Fill", listOf(a, b))
        val writes = store.writes
        val second = repo(store)
        assertEquals(first.catalog.value, second.catalog.value)
        assertEquals(writes, store.writes)
    }

    @Test fun addLightTrimsNameAndNormalizesAddress() = runTest {
        val repository = repo(FakeStore())
        assertEquals(CatalogResult.Saved(), repository.addLight(" ${c.lowercase()} ", "  Back  "))
        assertEquals(KnownLight(c, "Back"), repository.catalog.value.lights.last())
    }

    @Test fun addLightRejectsDuplicatesAndBadNames() = runTest {
        val store = FakeStore()
        val repository = repo(store)
        val before = repository.catalog.value
        assertRejected(repository.addLight(a.lowercase(), "Again"))
        assertRejected(repository.addLight(c, "   "))
        assertRejected(repository.addLight(c, "x".repeat(25)))
        assertEquals(before, repository.catalog.value)
        assertEquals(1, store.writes)
        assertEquals(CatalogResult.Saved(), repository.addLight(c, "x".repeat(24)))
    }

    @Test fun renameChangesOnlyThatLight() = runTest {
        val repository = repo(FakeStore())
        assertEquals(CatalogResult.Saved(), repository.rename(b.lowercase(), " Fill "))
        assertEquals(listOf("Light 1", "Fill"), repository.catalog.value.lights.map { it.name })
        assertRejected(repository.rename(c, "Nobody"))
        assertRejected(repository.rename(a, ""))
    }

    @Test fun createGroupKeepsCatalogOrderDedupesAndReturnsTheId() = runTest {
        val repository = repo(FakeStore()).withThree()
        val result = repository.createGroup("  Key and Back ", listOf(c, a, c, a.lowercase()))
        assertEquals(CatalogResult.Saved("g1"), result)
        assertEquals(LightGroup("g1", "Key and Back", listOf(a, c)), repository.catalog.value.group("g1"))
    }

    @Test fun groupsMayHoldOneLightAndOverlap() = runTest {
        val repository = repo(FakeStore())
        assertEquals(CatalogResult.Saved("g1"), repository.createGroup("Solo", listOf(a)))
        assertEquals(CatalogResult.Saved("g2"), repository.createGroup("Pair", listOf(a, b)))
        assertEquals(2, repository.catalog.value.groups.size)
    }

    @Test fun createGroupRejectsInvalidInput() = runTest {
        val repository = repo(FakeStore())
        repository.createGroup("Key", listOf(a))
        listOf(
            repository.createGroup(" ", listOf(a)),
            repository.createGroup("y".repeat(25), listOf(a)),
            repository.createGroup("KEY", listOf(b)),
            repository.createGroup("Empty", emptyList()),
            repository.createGroup("Ghost", listOf(c)),
        ).forEach(::assertRejected)
        assertEquals(1, repository.catalog.value.groups.size)
    }

    @Test fun updateGroupKeepsIdAndMayKeepItsOwnName() = runTest {
        val repository = repo(FakeStore())
        repository.createGroup("Key", listOf(a))
        assertEquals(CatalogResult.Saved(), repository.updateGroup("g1", "key", listOf(b, a)))
        assertEquals(LightGroup("g1", "key", listOf(a, b)), repository.catalog.value.group("g1"))
        assertEquals(CatalogResult.Saved(), repository.updateGroup("g1", "Renamed", listOf(b)))
        assertEquals(LightGroup("g1", "Renamed", listOf(b)), repository.catalog.value.groups.single())
        assertRejected(repository.updateGroup("missing", "X", listOf(a)))
    }

    @Test fun deleteGroupLeavesLightsAndOtherGroups() = runTest {
        val repository = repo(FakeStore())
        repository.createGroup("Both", listOf(a, b))
        repository.createGroup("Key", listOf(a))
        assertEquals(CatalogResult.Saved(), repository.deleteGroup("g1"))
        assertEquals(listOf(a, b), repository.catalog.value.addresses)
        assertEquals(listOf("g2"), repository.catalog.value.groups.map { it.id })
        assertRejected(repository.deleteGroup("g1"))
    }

    @Test fun failedWriteIsRejectedAndNotPublished() = runTest {
        val store = FakeStore()
        val repository = repo(store)
        repository.createGroup("Key", listOf(a))
        val before = repository.catalog.value
        store.failWrites = true
        assertRejected(repository.addLight(c, "Back"))
        assertRejected(repository.rename(a, "New"))
        assertRejected(repository.createGroup("Other", listOf(b)))
        assertRejected(repository.deleteGroup("g1"))
        assertEquals(before, repository.catalog.value)
    }

    @Test fun unreadableStoreBlocksMutationsUntilExplicitReset() = runTest {
        val store = FakeStore(StoredCatalog("not json", 2, null, emptyMap()))
        val repository = repo(store)
        assertNotNull(repository.problem.value)
        assertEquals(LightCatalog.EMPTY, repository.catalog.value)
        assertRejected(repository.addLight(a, "Key"))
        assertRejected(repository.createGroup("Key", listOf(a)))
        assertEquals(0, store.writes)
        assertEquals(CatalogResult.Saved(), repository.resetUnreadable())
        assertEquals(LightCatalogCodec.seeded(), repository.catalog.value)
        assertNull(repository.problem.value)
        assertEquals(1, store.writes)
    }
}
