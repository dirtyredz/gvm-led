package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.connection.Vectors
import com.dirtyredz.gvmled.light.protocol.FullStateReport
import com.dirtyredz.gvmled.light.protocol.LegacyFrameDecoder
import com.dirtyredz.gvmled.light.protocol.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMatcherTest {
    private fun report(text: String) = LegacyFrameDecoder().feed(hex(text)).single() as FullStateReport

    private val hsi = report("4C 54 0E 00 33 03 01 0B 32 2C 30 64 02 01 01 DC E4")
    private val hsiSat50 = report("4C 54 0E 00 33 03 01 0B 32 2C 30 32 02 01 01 8E 46")
    private val cct = report("4C 54 0E 00 33 03 01 0B 32 20 3C 64 01 01 01 05 7C")
    private val scene19 = report("4C 54 0E 00 33 03 01 0B 32 38 00 00 03 13 14 4F B9")
    private val scene20 = report("4C 54 0E 00 33 03 01 0B 32 38 00 00 03 14 05 D4 3E")
    private val offCct = report("4C 54 0E 00 33 03 00 0B 1E 38 00 14 01 14 05 BC B6")
    private val range = CctRange(32, 56)

    private val wantedHsi = WantedState(true, LightMode.Hsi, 50, cct = 44, hue = 48, saturation = 100)

    @Test fun hsiWantedMatchesTheHsiReport() {
        assertTrue(StateMatcher.matches(wantedHsi, hsi))
        assertEquals(emptyList<String>(), StateMatcher.mismatches(wantedHsi, hsi))
    }

    @Test fun reportedFullCircleHueMatchesZeroHue() {
        val fullCircle = LegacyFrameDecoder().feed(
            Vectors.framed("4C 54 0E 00 33 03 01 0B 32 2C 48 64 02 01 01"),
        ).single() as FullStateReport
        assertTrue(StateMatcher.matches(wantedHsi.copy(hue = 0), fullCircle))
    }

    @Test fun inactiveCctIsIgnoredInHsiMode() {
        assertTrue(StateMatcher.matches(wantedHsi.copy(cct = 20), hsi))
    }

    @Test fun cctWantedIgnoresHueAndSaturation() {
        val wanted = WantedState(true, LightMode.Cct, 50, cct = 32, hue = 0, saturation = 0)
        assertTrue(StateMatcher.matches(wanted, cct))
    }

    @Test fun saturationMismatchIsNamed() {
        assertEquals(listOf("saturation"), StateMatcher.mismatches(wantedHsi, hsiSat50))
    }

    @Test fun powerModeAndBrightnessMismatchesAreNamed() {
        assertEquals(listOf("power"), StateMatcher.mismatches(wantedHsi.copy(power = false), hsi))
        assertEquals(listOf("mode"), StateMatcher.mismatches(wantedHsi.copy(mode = LightMode.Cct), hsi))
        assertEquals(listOf("brightness"), StateMatcher.mismatches(wantedHsi.copy(brightness = 40), hsi))
    }

    @Test fun sceneNineteenComparesCctAndEffectTime() {
        val wanted = WantedState(true, LightMode.Scene, 50, cct = 56, scene = 19, effectTime = 20)
        assertTrue(StateMatcher.matches(wanted, scene19))
        assertEquals(listOf("effect time"), StateMatcher.mismatches(wanted.copy(effectTime = 10), scene19))
    }

    @Test fun sceneTwentyComparesHueSaturationAndEffectTime() {
        val wanted = WantedState(true, LightMode.Scene, 50, hue = 0, saturation = 0, scene = 20, effectTime = 5)
        assertTrue(StateMatcher.matches(wanted, scene20))
        assertEquals(listOf("hue"), StateMatcher.mismatches(wanted.copy(hue = 7), scene20))
    }

    @Test fun powerOffComparesPower() {
        val wanted = WantedState(false, LightMode.Cct, 30, cct = 56)
        assertTrue(StateMatcher.matches(wanted, offCct))
        assertFalse(StateMatcher.matches(wanted.copy(power = true), offCct))
    }

    @Test fun fromReportCopiesEveryFieldOfAnOffSceneReport() {
        assertEquals(
            WantedState(false, LightMode.Cct, 30, cct = 56, hue = 0, saturation = 20, scene = 20, effectTime = 5),
            WantedState.fromReport(offCct, range),
        )
    }

    @Test fun fromReportDropsACctOutsideTheLightsRange() {
        val state = WantedState.fromReport(cct, CctRange(40, 50))!!
        assertNull(state.cct)
        assertEquals(60, state.hue)
    }

    @Test fun fromReportKeepsSceneOneWithEffectTimeOne() {
        val state = WantedState.fromReport(hsi, range)!!
        assertEquals(1, state.scene)
        assertEquals(1, state.effectTime)
        assertEquals(LightMode.Hsi, state.mode)
    }
}
