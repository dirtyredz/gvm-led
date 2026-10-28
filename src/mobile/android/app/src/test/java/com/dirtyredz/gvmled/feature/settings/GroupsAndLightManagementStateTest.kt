package com.dirtyredz.gvmled.feature.settings

import com.dirtyredz.gvmled.connection.Candidate
import com.dirtyredz.gvmled.connection.LightConnection
import com.dirtyredz.gvmled.connection.LinkStatus
import com.dirtyredz.gvmled.connection.RadioState
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup
import com.dirtyredz.gvmled.data.repository.LightEntry
import com.dirtyredz.gvmled.feature.settings.groups.GroupDraft
import com.dirtyredz.gvmled.feature.settings.groups.groupsState
import com.dirtyredz.gvmled.feature.settings.lights.Inputs
import com.dirtyredz.gvmled.feature.settings.lights.managementState
import com.dirtyredz.gvmled.feature.settings.lights.seenText
import com.dirtyredz.gvmled.feature.settings.lights.suggestedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupsAndLightManagementStateTest {
    private val a = "94:C9:60:E5:D7:6F"
    private val b = "94:C9:60:EB:67:E6"
    private val c = "94:C9:60:AA:BB:CC"

    private val catalog = LightCatalog(
        listOf(KnownLight(a, "Key"), KnownLight(b, "Fill"), KnownLight(c, "Back")),
        listOf(LightGroup("g1", "Key and Fill", listOf(a, b))),
    )
    private val entries = listOf(
        LightEntry(LightConnection(a, LinkStatus.Live), "Key"),
        LightEntry(LightConnection(b, LinkStatus.Searching), "Fill"),
        LightEntry(LightConnection(c), "Back"),
    )

    private fun named(vararg names: String) = LightCatalog(names.mapIndexed { i, n -> KnownLight("94:C9:60:00:00:0$i", n) }, emptyList())

    @Test fun rowsListMemberNames() {
        val state = groupsState(catalog, entries, null)
        assertEquals("Key, Fill", state.groups.single().members)
        assertEquals("Key and Fill", state.groups.single().name)
        assertNull(state.editor)
        assertTrue(state.canCreate)
    }

    @Test fun editorOffersOfflineAndLiveLightsWithDraftSelection() {
        val draft = GroupDraft("g1", "Key and Fill", setOf(a, c), error = "Oops")
        val editor = groupsState(catalog, entries, draft).editor!!
        assertEquals("g1", editor.groupId)
        assertEquals("Oops", editor.error)
        assertEquals(listOf("Live", "Not connected", "Not connected"), editor.options.map { it.availability })
        assertEquals(listOf(true, false, true), editor.options.map { it.selected })
        assertEquals("Key …D7:6F", editor.options.first().label)
    }

    @Test fun lightsWithoutAnyEntryAreStillOffered() {
        val editor = groupsState(catalog, emptyList(), GroupDraft(null, "", emptySet())).editor!!
        assertEquals(3, editor.options.size)
        assertTrue(editor.options.all { it.availability == "Not connected" && !it.selected })
    }

    @Test fun cannotCreateWithoutLights() {
        assertFalse(groupsState(LightCatalog.EMPTY, emptyList(), null).canCreate)
    }

    @Test fun suggestedNameUsesTheNextNumberAndFillsGaps() {
        assertEquals("Light 3", suggestedName(named("Light 1", "Light 2")))
        assertEquals("Light 2", suggestedName(named("Light 1", "Light 3")))
        assertEquals("Light 1", suggestedName(LightCatalog.EMPTY))
    }

    @Test fun seenTextRoundsToSeconds() {
        assertEquals("seen just now", seenText(500))
        assertEquals("seen 12 s ago", seenText(12_000))
        assertEquals("seen just now", seenText(-5_000))
    }

    @Test fun managementStateMapsCandidatesAndLightStatuses() {
        val inputs = Inputs(RadioState.Ready, entries, catalog, problem = null, message = "Added")
        val state = managementState(inputs, listOf(Candidate("94:C9:60:11:22:33", 88_000)), finding = true, now = 100_000)
        assertEquals(listOf("Live", "Searching", "Not connected"), state.lights.map { it.status })
        assertEquals(listOf("Key", "Fill", "Back"), state.lights.map { it.name })
        assertEquals("D7:6F", state.lights.first().addressSuffix)
        assertEquals("seen 12 s ago", state.candidates.single().seen)
        assertTrue(state.finding)
        assertEquals("Added", state.message)
        assertEquals("Light 1", state.suggestedName)
    }
}
