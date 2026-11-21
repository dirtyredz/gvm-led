package com.dirtyredz.gvmled.light.protocol

import kotlin.math.roundToInt

/**
 * The one home for the legacy protocol's unit rules: hue in 5-degree steps (72 steps, where the
 * light may report step 72 meaning 360 degrees, the same colour as 0), colour temperature in
 * 100 K units, effect time in 100 ms units. Nothing else converts or wraps these values.
 */
object WireUnits {
    const val HUE_STEPS = 72
    const val DEGREES_PER_HUE_STEP = 5
    const val KELVIN_PER_CCT_UNIT = 100
    const val MILLIS_PER_EFFECT_TIME_UNIT = 100

    /** The editable hue steps, 0..71; step [HUE_STEPS] is only ever a reported alias of 0. */
    val EDITABLE_HUES: IntRange = 0 until HUE_STEPS

    /** Wraps a reported hue step (72 = 360 degrees) onto its canonical editable step (0). */
    fun normalizeHue(step: Int): Int = if (step == HUE_STEPS) 0 else step

    /** Degrees for a hue step, after normalizing so a reported 72 reads 0. */
    fun hueDegrees(step: Int): Int = normalizeHue(step) * DEGREES_PER_HUE_STEP

    /** Snaps degrees (any real, negative allowed) to the nearest editable hue step. */
    fun hueFromDegrees(degrees: Double): Int {
        val steps = (degrees / DEGREES_PER_HUE_STEP).roundToInt()
        return ((steps % HUE_STEPS) + HUE_STEPS) % HUE_STEPS
    }

    fun cctKelvin(unit: Int): Int = unit * KELVIN_PER_CCT_UNIT

    fun cctUnit(kelvin: Int): Int = kelvin / KELVIN_PER_CCT_UNIT

    fun effectTimeMillis(unit: Int): Int = unit * MILLIS_PER_EFFECT_TIME_UNIT
}
