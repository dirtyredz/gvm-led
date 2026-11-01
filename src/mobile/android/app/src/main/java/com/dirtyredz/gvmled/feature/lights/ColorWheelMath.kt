package com.dirtyredz.gvmled.feature.lights

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A wheel selection in protocol units: hue in 5-degree steps (0..71), saturation 0..100. */
internal data class HueSat(val hue: Int, val saturation: Int)

/** Pure geometry for the hue/saturation wheel; angle 0 is 3 o'clock, increasing clockwise (screen y down). */
internal object ColorWheelMath {
    const val HUE_STEPS = 72
    const val DEGREES_PER_STEP = 5

    /** The wheel point (relative to the centre, y down) as a snapped selection; points past the rim clamp to it. */
    fun fromOffset(dx: Float, dy: Float, radius: Float): HueSat {
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val saturation = if (radius <= 0f) 0 else (hypot(dx, dy) / radius * 100f).roundToInt().coerceIn(0, 100)
        return HueSat(snapHue(degrees), saturation)
    }

    /** Snaps degrees (any real, negative allowed) to the nearest protocol hue step, wrapping 360 to 0. */
    fun snapHue(degrees: Double): Int {
        val steps = (degrees / DEGREES_PER_STEP).roundToInt()
        return ((steps % HUE_STEPS) + HUE_STEPS) % HUE_STEPS
    }

    /** Offset from the wheel centre (y down) of a selection, for placing the thumb. */
    fun toOffset(selection: HueSat, radius: Float): Pair<Float, Float> {
        val angle = selection.hue * DEGREES_PER_STEP * PI / 180.0
        val distance = radius * selection.saturation.coerceIn(0, 100) / 100f
        return (distance * cos(angle)).toFloat() to (distance * sin(angle)).toFloat()
    }
}
