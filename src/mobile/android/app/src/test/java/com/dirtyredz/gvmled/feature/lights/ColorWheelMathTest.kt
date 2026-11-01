package com.dirtyredz.gvmled.feature.lights

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ColorWheelMathTest {
    private val r = 100f

    @Test fun cardinalAnglesMapToHue() {
        assertEquals(HueSat(0, 100), ColorWheelMath.fromOffset(100f, 0f, r))
        assertEquals(HueSat(18, 100), ColorWheelMath.fromOffset(0f, 100f, r))
        assertEquals(HueSat(36, 100), ColorWheelMath.fromOffset(-100f, 0f, r))
        assertEquals(HueSat(54, 100), ColorWheelMath.fromOffset(0f, -100f, r))
    }

    @Test fun distanceMapsToSaturationAndClamps() {
        assertEquals(0, ColorWheelMath.fromOffset(0f, 0f, r).saturation)
        assertEquals(50, ColorWheelMath.fromOffset(50f, 0f, r).saturation)
        assertEquals(100, ColorWheelMath.fromOffset(500f, 0f, r).saturation)
        assertEquals(0, ColorWheelMath.fromOffset(10f, 10f, 0f).saturation)
    }

    @Test fun hueSnapsToFiveDegreesAndWraps() {
        assertEquals(1, ColorWheelMath.snapHue(3.0))
        assertEquals(0, ColorWheelMath.snapHue(2.4))
        assertEquals(0, ColorWheelMath.snapHue(359.0))
        assertEquals(71, ColorWheelMath.snapHue(-5.0))
        assertEquals(71, ColorWheelMath.snapHue(357.4))
    }

    @Test fun offsetRoundTripsEverySelection() {
        for (hue in 0 until ColorWheelMath.HUE_STEPS) for (sat in listOf(20, 55, 100)) {
            val (x, y) = ColorWheelMath.toOffset(HueSat(hue, sat), 200f)
            assertEquals(HueSat(hue, sat), ColorWheelMath.fromOffset(x, y, 200f))
        }
        val (x, y) = ColorWheelMath.toOffset(HueSat(0, 0), 200f)
        assertTrue(x == 0f && y == 0f)
    }
}
