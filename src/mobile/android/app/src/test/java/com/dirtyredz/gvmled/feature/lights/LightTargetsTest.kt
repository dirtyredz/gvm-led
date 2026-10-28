package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.connection.LightConnection
import com.dirtyredz.gvmled.connection.RadioState
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup
import com.dirtyredz.gvmled.data.repository.LightEntry
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightTargetsTest {
    private val a = "94:C9:60:E5:D7:6F"
    private val b = "94:C9:60:EB:67:E6"
    private val c = "94:C9:60:AA:BB:CC"

    private val catalog = LightCatalog(
        listOf(KnownLight(a, "Key"), KnownLight(b, "Fill"), KnownLight(c, "Back")),
        listOf(LightGroup("g1", "Key and Fill", listOf(a, b)), LightGroup("g2", "Solo", listOf(c))),
    )
    private val entries = catalog.lights.map { LightEntry(LightConnection(it.address), it.name) }

    private fun state(selected: String?, known: LightCatalog = catalog) =
        lightsState(RadioState.Ready, if (known === catalog) entries else emptyList(), known, selected, ZoneOffset.UTC)

    @Test fun resolveKeepsAValidSelection() {
        assertEquals(TargetKeys.light(b), resolveTarget(TargetKeys.light(b), catalog))
        assertEquals(TargetKeys.group("g2"), resolveTarget(TargetKeys.group("g2"), catalog))
    }

    @Test fun resolveFallsBackToTheFirstLightWhenTheGroupWasDeleted() {
        val withoutGroup = catalog.copy(groups = emptyList())
        assertEquals(TargetKeys.light(a), resolveTarget(TargetKeys.group("g1"), withoutGroup))
        assertEquals(TargetKeys.light(a), resolveTarget(null, catalog))
    }

    @Test fun resolveIsNullWithoutLightsAndNeverInventsABothTarget() {
        assertNull(resolveTarget(TargetKeys.group("g1"), LightCatalog.EMPTY))
        assertNull(resolveTarget(null, LightCatalog.EMPTY))
        val keys = state(null).targets.map { it.key }
        assertEquals(setOf(TargetKeys.light(a), TargetKeys.light(b), TargetKeys.light(c), TargetKeys.group("g1"), TargetKeys.group("g2")), keys.toSet())
    }

    @Test fun chipsAreAllLightsThenAllGroupsWithOneSelected() {
        val chips = state(TargetKeys.group("g2")).targets
        assertEquals(listOf("Key", "Fill", "Back", "Key and Fill", "Solo"), chips.map { it.label })
        assertEquals(listOf(false, false, false, true, true), chips.map { it.isGroup })
        assertEquals(listOf("Solo"), chips.filter { it.selected }.map { it.label })
    }

    @Test fun selectingAGroupShowsOnlyItsMembersAndASummary() {
        val result = state(TargetKeys.group("g1"))
        assertEquals(listOf(a, b), result.lights.map { it.address })
        assertEquals("Key and Fill: 2 lights", result.groupSummary)
        assertFalse(result.isEmpty)
    }

    @Test fun aOneMemberGroupSummaryIsSingular() {
        val result = state(TargetKeys.group("g2"))
        assertEquals(listOf(c), result.lights.map { it.address })
        assertEquals("Solo: 1 light", result.groupSummary)
    }

    @Test fun selectingAnIndividualShowsOnlyThatLight() {
        val result = state(TargetKeys.light(b))
        assertEquals(listOf(b), result.lights.map { it.address })
        assertEquals("Fill", result.lights.single().name)
        assertNull(result.groupSummary)
        assertEquals(1, result.targets.count { it.selected })
    }

    @Test fun emptyCatalogIsEmptyWithNoCards() {
        val result = state(TargetKeys.light(a), LightCatalog.EMPTY)
        assertTrue(result.isEmpty)
        assertTrue(result.lights.isEmpty())
        assertTrue(result.targets.isEmpty())
        assertNull(result.groupSummary)
    }
}
