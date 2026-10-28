package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.data.local.WantedStateCodec
import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup
import com.dirtyredz.gvmled.light.control.CctRange
import com.dirtyredz.gvmled.light.control.LightEdit
import com.dirtyredz.gvmled.light.control.LightMode
import com.dirtyredz.gvmled.light.control.WantedRecord
import com.dirtyredz.gvmled.light.protocol.FullStateReport
import com.dirtyredz.gvmled.light.protocol.LegacyFrameDecoder
import com.dirtyredz.gvmled.light.protocol.hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LightControlRepositoryTest {
    private val a = "94:C9:60:E5:D7:6F"
    private val b = "94:C9:60:EB:67:E6"
    private val c = "94:C9:60:AA:BB:CC"

    private fun report(text: String) = LegacyFrameDecoder().feed(hex(text)).single() as FullStateReport

    private val hsiReport = report("4C 54 0E 00 33 03 01 0B 32 2C 30 64 02 01 01 DC E4")
    private val cctReport = report("4C 54 0E 00 33 03 01 0B 32 20 3C 64 01 01 01 05 7C")
    private val sceneReport = report("4C 54 0E 00 33 03 01 0B 32 38 00 00 03 14 05 D4 3E")

    private class FakeStore(var text: String? = null) : WantedStateStore {
        var failWrites = false
        var onWrite: () -> Unit = {}
        override fun read() = text
        override fun write(text: String): Boolean {
            if (failWrites) return false
            onWrite()
            this.text = text
            return true
        }
    }

    private val catalog = MutableStateFlow(
        LightCatalog(
            lights = listOf(KnownLight(a, "Key"), KnownLight(b, "Fill"), KnownLight(c, "Back")),
            groups = listOf(LightGroup("g1", "Front", listOf(a, b)), LightGroup("g2", "Rear", listOf(b, c))),
        ),
    )
    private val ranges = mutableMapOf<String, CctRange?>(a to CctRange(32, 56), b to CctRange(27, 65), c to null)
    private val g1 = ControlTarget.Group("g1")
    private val g2 = ControlTarget.Group("g2")

    private fun TestScope.repo(store: FakeStore) =
        LightControlRepository(store, UnconfinedTestDispatcher(testScheduler), catalog) { ranges }

    /** Seeds a (HSI), b (CCT) and optionally c (scene) at revision 1. */
    private suspend fun LightControlRepository.seedAll(includeC: Boolean = true) {
        seed(a, hsiReport, CctRange(32, 56))
        seed(b, cctReport, CctRange(27, 65))
        if (includeC) seed(c, sceneReport, CctRange(27, 65))
    }

    private fun assertRejected(result: ControlResult): ControlResult.Rejected {
        assertTrue("expected Rejected, got $result", result is ControlResult.Rejected)
        return result as ControlResult.Rejected
    }

    @Test fun seedStoresRevisionOneFromTheReport() = runTest {
        val store = FakeStore()
        val repository = repo(store)
        val record = repository.seed(a.lowercase(), hsiReport, CctRange(32, 56))
        assertEquals(1L, record?.revision)
        assertEquals(LightMode.Hsi, record?.state?.mode)
        assertEquals(mapOf(a to record), repository.wanted.value)
        assertEquals(repository.wanted.value, WantedStateCodec.decode(store.text))
    }

    @Test fun seedIsANoOpOnceIntentExists() = runTest {
        val repository = repo(FakeStore())
        repository.seed(a, hsiReport, CctRange(32, 56))
        assertNull(repository.seed(a, cctReport, CctRange(32, 56)))
        assertEquals(LightMode.Hsi, repository.wanted.value.getValue(a).state.mode)
    }

    @Test fun groupBrightnessChangesOnlyBrightnessAndBumpsEachRevision() = runTest {
        val store = FakeStore()
        val repository = repo(store)
        repository.seedAll()
        val before = repository.wanted.value
        assertEquals(ControlResult.Accepted(listOf(a, b)), repository.apply(g1, LightEdit(brightness = 20)))
        val after = repository.wanted.value
        assertEquals(before.getValue(a).state.copy(brightness = 20), after.getValue(a).state)
        assertEquals(before.getValue(b).state.copy(brightness = 20), after.getValue(b).state)
        assertEquals(LightMode.Hsi, after.getValue(a).state.mode)
        assertEquals(LightMode.Cct, after.getValue(b).state.mode)
        assertEquals(2L, after.getValue(a).revision)
        assertEquals(2L, after.getValue(b).revision)
        assertEquals(before.getValue(c), after.getValue(c))
        assertEquals(after, WantedStateCodec.decode(store.text))
    }

    @Test fun changeIsPersistedBeforeItIsPublished() = runTest {
        val store = FakeStore()
        val repository = repo(store)
        repository.seedAll()
        val before = repository.wanted.value
        var publishedDuringWrite: Map<String, WantedRecord>? = null
        store.onWrite = { publishedDuringWrite = repository.wanted.value }
        repository.apply(g1, LightEdit(brightness = 20))
        assertEquals(before, publishedDuringWrite)
        assertTrue(repository.wanted.value != before)
    }

    @Test fun groupWithAMemberWithoutIntentIsRejectedNamingIt() = runTest {
        val repository = repo(FakeStore())
        repository.seedAll(includeC = false)
        val before = repository.wanted.value
        val rejected = assertRejected(repository.apply(g2, LightEdit(brightness = 20)))
        assertTrue(rejected.reason, "Back" in rejected.reason)
        assertEquals(before, repository.wanted.value)
    }

    @Test fun cctEditIsRejectedWhenAMemberHasNoRange() = runTest {
        val repository = repo(FakeStore())
        repository.seedAll()
        val before = repository.wanted.value
        val rejected = assertRejected(repository.apply(g2, LightEdit(cct = 40)))
        assertTrue(rejected.reason, "Back" in rejected.reason)
        assertEquals(before, repository.wanted.value)
    }

    @Test fun cctOutsideAMembersRangeIsRejectedNamingTheRange() = runTest {
        val repository = repo(FakeStore())
        repository.seedAll()
        val before = repository.wanted.value
        val rejected = assertRejected(repository.apply(g1, LightEdit(cct = 30)))
        assertTrue(rejected.reason, "Key supports only 3200-5600 K" in rejected.reason)
        assertEquals(before, repository.wanted.value)
    }

    @Test fun failedWriteRejectsAndLeavesWantedUnchanged() = runTest {
        val store = FakeStore()
        val repository = repo(store)
        repository.seedAll()
        val before = repository.wanted.value
        val text = store.text
        store.failWrites = true
        assertRejected(repository.apply(g1, LightEdit(brightness = 20)))
        assertEquals(before, repository.wanted.value)
        assertEquals(text, store.text)
    }

    @Test fun overlappingGroupsKeepTheLatestValueAndCountEveryEdit() = runTest {
        val repository = repo(FakeStore())
        repository.seedAll()
        repository.apply(g1, LightEdit(brightness = 20))
        repository.apply(g2, LightEdit(brightness = 60))
        val wanted = repository.wanted.value
        assertEquals(20, wanted.getValue(a).state.brightness)
        assertEquals(60, wanted.getValue(b).state.brightness)
        assertEquals(60, wanted.getValue(c).state.brightness)
        assertEquals(3L, wanted.getValue(b).revision)
    }

    @Test fun unreadableStoredTextPausesChangesUntilReset() = runTest {
        val store = FakeStore("not json")
        val repository = repo(store)
        assertNotNull(repository.problem.value)
        assertNull(repository.seed(a, hsiReport, CctRange(32, 56)))
        assertRejected(repository.apply(ControlTarget.Light(a), LightEdit(brightness = 20)))
        assertTrue(repository.resetUnreadable())
        assertNull(repository.problem.value)
        assertEquals(emptyMap<String, WantedRecord>(), repository.wanted.value)
        assertEquals(1L, repository.seed(a, hsiReport, CctRange(32, 56))?.revision)
    }

    @Test fun acceptedResultListsMembersInCatalogOrder() = runTest {
        val repository = repo(FakeStore())
        repository.seedAll()
        assertEquals(ControlResult.Accepted(listOf(a, b)), repository.apply(g1, LightEdit(power = false)))
        assertEquals(ControlResult.Accepted(listOf(b, c)), repository.apply(g2, LightEdit(power = true)))
    }
}
