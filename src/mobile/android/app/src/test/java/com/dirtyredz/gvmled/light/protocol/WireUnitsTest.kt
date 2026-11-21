package com.dirtyredz.gvmled.light.protocol

import org.junit.Test
import org.junit.Assert.assertEquals

class WireUnitsTest {
    @Test fun hueStep72IsTheSameColourAs0() {
        assertEquals(Hue(0), Hue(72))
        assertEquals(0, Hue(72).step)
        assertEquals(0, WireUnits.hueDegrees(Hue(72)))
        assertEquals(71, Hue(71).step)
        assertEquals(355, WireUnits.hueDegrees(Hue(71)))
    }

    @Test fun degreesSnapAndWrap() {
        assertEquals(Hue(0), WireUnits.hueFromDegrees(360.0))
        assertEquals(Hue(71), WireUnits.hueFromDegrees(-5.0))
        assertEquals(Hue(1), WireUnits.hueFromDegrees(7.4))
    }

    @Test fun cctAndEffectTimeUnits() {
        assertEquals(3200, WireUnits.cctKelvin(32))
        assertEquals(32, WireUnits.cctUnit(3200))
        assertEquals(2500, WireUnits.effectTimeMillis(25))
    }
}
