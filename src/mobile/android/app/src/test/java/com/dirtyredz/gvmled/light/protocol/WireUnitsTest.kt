package com.dirtyredz.gvmled.light.protocol

import org.junit.Test
import org.junit.Assert.assertEquals

class WireUnitsTest {
    @Test fun hueStep72IsTheSameColourAs0() {
        assertEquals(0, WireUnits.normalizeHue(72))
        assertEquals(0, WireUnits.hueDegrees(72))
        assertEquals(71, WireUnits.normalizeHue(71))
        assertEquals(355, WireUnits.hueDegrees(71))
    }

    @Test fun degreesSnapAndWrap() {
        assertEquals(0, WireUnits.hueFromDegrees(360.0))
        assertEquals(71, WireUnits.hueFromDegrees(-5.0))
        assertEquals(1, WireUnits.hueFromDegrees(7.4))
    }

    @Test fun cctAndEffectTimeUnits() {
        assertEquals(3200, WireUnits.cctKelvin(32))
        assertEquals(32, WireUnits.cctUnit(3200))
        assertEquals(2500, WireUnits.effectTimeMillis(25))
    }
}
