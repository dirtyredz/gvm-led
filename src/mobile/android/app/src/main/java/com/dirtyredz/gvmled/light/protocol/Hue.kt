package com.dirtyredz.gvmled.light.protocol

/**
 * A hue in 5-degree steps. Step [WireUnits.HUE_STEPS] (a light's report of 360 degrees) is the same
 * colour as step 0, so it can only be built as 0: a noncanonical hue cannot exist, and equal colours
 * are equal values. Build one with `Hue(step)`; the raw [step] is for the protocol and persistence
 * edges only. The legal range (0-71) is checked by `Field.Hue`, as for every other wanted value.
 */
@JvmInline
value class Hue private constructor(val step: Int) {
    companion object {
        operator fun invoke(step: Int): Hue = Hue(if (step == WireUnits.HUE_STEPS) 0 else step)
    }
}
