package com.dirtyredz.gvmled.feature.lights

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A wheel selection in protocol units: hue in 5-degree steps (0..71), saturation 0..100. */
internal data class HueSat(val hue: Int, val saturation: Int)

/**
 * A selection the owner made, remembered with the input (wanted) value ([base]) it was made against. The
 * holder state must outlive input-value changes (a pointerInput block captures it once), so staleness
 * is decided here instead of by re-keying the state.
 */
internal data class Pick(val value: HueSat, val base: HueSat?)

/** What the wheel shows: the owner's pick while the input (wanted) value is still the one it was made against, else the input value. */
internal fun displayedSelection(known: HueSat?, pick: Pick?): HueSat? =
    if (pick != null && pick.base == known) pick.value else known

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

    /** Drawn/hit-test wheel radius for a square of [side] px, leaving [inset] px inside the bounds for the thumb at the rim. */
    fun wheelRadius(side: Float, inset: Float): Float = (side / 2f - inset).coerceAtLeast(0f)

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
