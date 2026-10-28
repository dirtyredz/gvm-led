package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup
import com.dirtyredz.gvmled.data.repository.LightEntry
import com.dirtyredz.gvmled.light.connection.LightConnection
import com.dirtyredz.gvmled.light.connection.LinkStatus
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.light.connection.Vectors
import com.dirtyredz.gvmled.light.control.ApplyStatus
import com.dirtyredz.gvmled.light.control.CctRange
import com.dirtyredz.gvmled.light.control.Field
import com.dirtyredz.gvmled.light.control.LightEdit
import com.dirtyredz.gvmled.light.control.LightMode
import com.dirtyredz.gvmled.light.control.Shared
import com.dirtyredz.gvmled.light.control.WantedRecord
import com.dirtyredz.gvmled.light.control.WantedState
import com.dirtyredz.gvmled.light.protocol.HelloReply
import com.dirtyredz.gvmled.light.protocol.LegacyFrameDecoder
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Lights screen's control state: mixed values, partial outcomes and the edits controls build. */
class LightControlsStateTest {
    private val a = "94:C9:60:E5:D7:6F"
    private val b = "94:C9:60:EB:67:E6"
    private val c = "94:C9:60:AA:BB:CC"
    private val hello = LegacyFrameDecoder().feed(Vectors.HELLO_REPLY).single() as HelloReply
    private val catalog = LightCatalog(
        listOf(KnownLight(a, "Key"), KnownLight(b, "Fill"), KnownLight(c, "Back")),
        listOf(LightGroup("g1", "Key and Fill", listOf(a, b)), LightGroup("g2", "All three", listOf(a, b, c))),
    )
    private val hsi = WantedState(true, LightMode.Hsi, 50, hue = 48, saturation = 100)

    private fun entry(address: String, name: String, state: WantedState?, apply: ApplyStatus, live: Boolean = true) = LightEntry(
        connection = LightConnection(
            address,
            if (live) LinkStatus.Live else LinkStatus.Searching,
            generation = 1,
            hello = hello,
            helloGeneration = 1,
        ),
        name = name,
        wanted = state?.let { WantedRecord(2, it) },
        apply = apply,
    )

    private val entries = listOf(
        entry(a, "Key", hsi, ApplyStatus.Confirmed(2)),
        entry(b, "Fill", hsi.copy(brightness = 20), ApplyStatus.WaitingForLight(2), live = false),
        entry(c, "Back", null, ApplyStatus.NoSettings),
    )

    private fun state(target: ControlTarget) = lightsState(RadioState.Ready, entries, catalog, target, ZoneOffset.UTC)

    @Test fun aGroupShowsSharedAndMixedValuesAndAPartialOutcome() {
        val result = state(ControlTarget.Group("g1"))
        val controls = result.controls!!
        assertEquals(Shared.Same(LightMode.Hsi), controls.mode)
        assertEquals(Shared.Same(48), controls.hue)
        assertEquals(Shared.Mixed, controls.brightness)
        assertEquals("Fill has not reported its colour temperature range yet", controls.cctUnavailable)
        assertEquals("1 of 2 confirmed; Fill waiting for connection", result.outcome)
        assertEquals(listOf("Confirmed", "Waiting for connection"), result.lights.map { it.apply })
    }

    @Test fun aMemberWithoutSettingsIsNamedAndBlocksControls() {
        val result = state(ControlTarget.Group("g2"))
        assertEquals(listOf(c), result.controls!!.withoutSettings)
        assertEquals("1 of 3 confirmed; Fill waiting for connection; Back no settings yet (adopted from its first report)", result.outcome)
    }

    @Test fun anIndividualOutcomeIsItsOwnStatus() {
        assertEquals("Confirmed", state(ControlTarget.Light(a)).outcome)
        assertEquals(CctRange(32, 56), state(ControlTarget.Light(a)).controls!!.cctRange)
        assertNull(state(ControlTarget.Light(a)).controls!!.cctUnavailable)
    }

    @Test fun anOfflineMembersOldHelloCannotEnableGroupCct() {
        val controls = state(ControlTarget.Group("g1")).controls!!
        assertNull(controls.cctRange)
        assertEquals("Fill has not reported its colour temperature range yet", controls.cctUnavailable)
        assertNull(state(ControlTarget.Light(b)).lights.single().cctRange)
    }

    @Test fun modeSwitchFillsOnlyGapsWithCommonOrVisibleStartingValues() {
        val values = state(ControlTarget.Light(a)).controls!!
        assertEquals(LightEdit(mode = LightMode.Cct, cct = 44, fillOnly = setOf(Field.Cct)), modeSwitch(LightMode.Cct, values))
        assertEquals(
            LightEdit(mode = LightMode.Hsi, hue = 48, saturation = 100, fillOnly = setOf(Field.Hue, Field.Saturation)),
            modeSwitch(LightMode.Hsi, values),
        )
        val groupValues = state(ControlTarget.Group("g1")).controls!!
        assertEquals(LightEdit(mode = LightMode.Cct, cct = null, fillOnly = setOf(Field.Cct)), modeSwitch(LightMode.Cct, groupValues))
    }

    @Test fun sceneSelectOffersOnlyTheFieldsThatSceneUses() {
        val values = state(ControlTarget.Light(a)).controls!!
        val scene20 = sceneSelect(20, values)
        assertEquals(LightEdit(mode = LightMode.Scene, scene = 20, hue = 48, saturation = 100, effectTime = 10,
            fillOnly = setOf(Field.Cct, Field.Hue, Field.Saturation, Field.EffectTime)), scene20)
        val scene19 = sceneSelect(19, values)
        assertEquals(44, scene19.cct)
        assertEquals(10, scene19.effectTime)
        assertNull(scene19.hue)
        val scene1 = sceneSelect(1, values)
        assertNull(scene1.cct)
        assertNull(scene1.effectTime)
    }
}
