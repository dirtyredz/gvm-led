package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.light.control.LightEdit
import com.dirtyredz.gvmled.light.control.TargetValues
import com.dirtyredz.gvmled.light.protocol.SceneCatalog

/**
 * Scene picker (1-20) plus only the parameters the common scene uses: effect time, CCT, and for
 * scene 20 hue and saturation. Parameters are hidden while the members' scenes differ.
 */
@Composable
internal fun SceneControls(values: TargetValues, apply: (LightEdit) -> Unit) {
    val current = values.scene.valueOrNull
    Text("Scene: " + sharedText(values.scene) { "$it" }, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SceneCatalog.IDS.forEach { scene ->
            FilterChip(selected = current == scene, onClick = { apply(sceneSelect(scene, values)) }, label = { Text("$scene") })
        }
    }
    if (current == null || !SceneCatalog.isSupported(current)) return
    val fields = SceneCatalog.fields(current)
    if (fields.effectTime) {
        SharedSlider("Effect time", values.effectTime, SceneCatalog.EFFECT_TIMES, { "${it * 100} ms" }, onCommit = {
            apply(LightEdit(effectTime = it))
        })
    }
    if (fields.cct) CctSlider(values, apply)
    if (fields.colour) HsiSliders(values, apply)
}
