package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.light.protocol.Hue
import com.dirtyredz.gvmled.light.protocol.WireUnits
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ColorWheelMathTest {
    private val r = 100f

    @Test fun cardinalAnglesMapToHue() {
        assertEquals(HueSat(Hue(0), 100), ColorWheelMath.fromOffset(100f, 0f, r))
        assertEquals(HueSat(Hue(18), 100), ColorWheelMath.fromOffset(0f, 100f, r))
        assertEquals(HueSat(Hue(36), 100), ColorWheelMath.fromOffset(-100f, 0f, r))
        assertEquals(HueSat(Hue(54), 100), ColorWheelMath.fromOffset(0f, -100f, r))
    }

    @Test fun distanceMapsToSaturationAndClamps() {
        assertEquals(0, ColorWheelMath.fromOffset(0f, 0f, r).saturation)
        assertEquals(50, ColorWheelMath.fromOffset(50f, 0f, r).saturation)
        assertEquals(100, ColorWheelMath.fromOffset(500f, 0f, r).saturation)
        assertEquals(0, ColorWheelMath.fromOffset(10f, 10f, 0f).saturation)
    }

    @Test fun wheelRadiusLeavesThumbRoomInsideBounds() {
        assertEquals(104f, ColorWheelMath.wheelRadius(240f, 16f), 0.001f)
        assertEquals(0f, ColorWheelMath.wheelRadius(20f, 16f), 0.001f)
        // rim selection stays inside the bounds including the thumb inset
        val (dx, _) = ColorWheelMath.toOffset(HueSat(Hue(0), 100), ColorWheelMath.wheelRadius(240f, 16f))
        assertTrue(120f + dx + 16f <= 240f + 0.001f)
    }

    @Test fun hueSnapsToFiveDegreesAndWraps() {
        assertEquals(Hue(1), WireUnits.hueFromDegrees(3.0))
        assertEquals(Hue(0), WireUnits.hueFromDegrees(2.4))
        assertEquals(Hue(0), WireUnits.hueFromDegrees(359.0))
        assertEquals(Hue(71), WireUnits.hueFromDegrees(-5.0))
        assertEquals(Hue(71), WireUnits.hueFromDegrees(357.4))
    }

    @Test fun offsetRoundTripsEverySelection() {
        for (hue in 0 until WireUnits.HUE_STEPS) for (sat in listOf(20, 55, 100)) {
            val (x, y) = ColorWheelMath.toOffset(HueSat(Hue(hue), sat), 200f)
            assertEquals(HueSat(Hue(hue), sat), ColorWheelMath.fromOffset(x, y, 200f))
        }
        val (x, y) = ColorWheelMath.toOffset(HueSat(Hue(0), 0), 200f)
        assertTrue(x == 0f && y == 0f)
    }

    @Test fun pickShownUntilInputValueChanges() {
        val a = HueSat(Hue(10), 50)
        val b = HueSat(Hue(20), 60)
        assertEquals(b, displayedSelection(a, Pick(b, a)))
        // the input (wanted) value changed: a stale pick must not mask it
        assertEquals(b, displayedSelection(b, Pick(HueSat(Hue(30), 70), a)))
        assertEquals(b, displayedSelection(null, Pick(b, null)))
        assertEquals(a, displayedSelection(a, null))
    }
}
