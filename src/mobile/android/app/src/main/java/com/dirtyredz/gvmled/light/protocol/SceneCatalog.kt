package com.dirtyredz.gvmled.light.protocol

/** Which settings a scene uses besides brightness (which every scene uses). */
data class SceneFields(val cct: Boolean, val colour: Boolean, val effectTime: Boolean)

/**
 * The `rgb33` scenes, from static evidence in the official app's `Activity/MainActivity.java`
 * (which controls it shows per scene), supplemented by the 2026-10-26 hardware proof of scenes 19
 * and 20 with effect times. Only those two are hardware-verified; scene 20's hue/saturation are
 * static evidence only.
 */
object SceneCatalog {
    val IDS: IntRange = 1..20

    /** Effect time in 100 ms units: 1-50 = 100-5000 ms. */
    val EFFECT_TIMES: IntRange = 1..50

    private val CCT_SCENES = setOf(5, 9, 10, 12, 19)
    private val TIME_SCENES = setOf(6, 7, 14, 15, 19, 20)
    private val COLOUR_SCENES = setOf(20)

    fun isSupported(scene: Int): Boolean = scene in IDS

    fun fields(scene: Int): SceneFields {
        require(isSupported(scene)) { "Unsupported scene $scene" }
        return SceneFields(cct = scene in CCT_SCENES, colour = scene in COLOUR_SCENES, effectTime = scene in TIME_SCENES)
    }
}
