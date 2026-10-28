package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.light.control.Field
import com.dirtyredz.gvmled.light.control.LightEdit
import com.dirtyredz.gvmled.light.control.LightMode
import com.dirtyredz.gvmled.light.protocol.SceneCatalog
import com.dirtyredz.gvmled.light.control.TargetValues

/**
 * The values offered to a member that has none when a mode or scene is selected: the target's
 * common value when there is one, otherwise a fixed visible starting point (hue 0, saturation 100,
 * 1 s effect time, the middle of the common CCT range). They only fill gaps ([LightEdit.fillOnly]);
 * a member's own stored value always wins. No CCT is offered without a common range.
 */
internal data class ModeFill(val cct: Int?, val hue: Int, val saturation: Int, val effectTime: Int) {
    companion object {
        fun from(values: TargetValues) = ModeFill(
            cct = values.cct.valueOrNull?.takeIf { cct -> values.cctRange?.let { cct in it } == true }
                ?: values.cctRange?.let { (it.min + it.max) / 2 },
            hue = values.hue.valueOrNull ?: 0,
            saturation = values.saturation.valueOrNull ?: 100,
            effectTime = values.effectTime.valueOrNull ?: 10,
        )
    }
}

/** Selects [mode] for every member, keeping each member's own values for it. */
internal fun modeSwitch(mode: LightMode, values: TargetValues): LightEdit {
    val fill = ModeFill.from(values)
    return when (mode) {
        LightMode.Cct -> LightEdit(mode = mode, cct = fill.cct, fillOnly = setOf(Field.Cct))
        LightMode.Hsi -> LightEdit(mode = mode, hue = fill.hue, saturation = fill.saturation, fillOnly = setOf(Field.Hue, Field.Saturation))
        LightMode.Scene -> sceneSelect(values.scene.valueOrNull?.takeIf(SceneCatalog::isSupported) ?: 1, values)
    }
}

/** Selects [scene] in scene mode, filling only the values that scene uses and a member lacks. */
internal fun sceneSelect(scene: Int, values: TargetValues): LightEdit {
    val fields = SceneCatalog.fields(scene)
    val fill = ModeFill.from(values)
    return LightEdit(
        mode = LightMode.Scene,
        scene = scene,
        cct = fill.cct.takeIf { fields.cct },
        hue = fill.hue.takeIf { fields.colour },
        saturation = fill.saturation.takeIf { fields.colour },
        effectTime = fill.effectTime.takeIf { fields.effectTime },
        fillOnly = setOf(Field.Cct, Field.Hue, Field.Saturation, Field.EffectTime),
    )
}
