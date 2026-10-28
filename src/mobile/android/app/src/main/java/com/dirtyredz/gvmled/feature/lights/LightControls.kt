package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.light.control.Field
import com.dirtyredz.gvmled.light.control.LightEdit
import com.dirtyredz.gvmled.light.control.LightMode
import com.dirtyredz.gvmled.light.control.Shared
import com.dirtyredz.gvmled.light.control.TargetValues
import kotlin.math.roundToInt

/**
 * Controls for the selected target, showing wanted values (one value, Mixed or Unknown). Sliders
 * apply when released, so a drag becomes one wanted revision. Mode-specific controls appear only
 * when every member is in the same mode.
 */
@Composable
internal fun LightControls(values: TargetValues, outcome: String?, message: String?, apply: (LightEdit) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            outcome?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (values.withoutSettings.isNotEmpty()) {
                Text(
                    "Controls unlock once every light has connected once and reported its settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            PowerRow(values.power, apply)
            ModeRow(values, apply)
            SharedSlider("Brightness", values.brightness, 0..100, { "$it %" }, onCommit = { apply(LightEdit(brightness = it)) })
            when (values.mode.valueOrNull) {
                LightMode.Cct -> CctSlider(values, apply)
                LightMode.Hsi -> HsiSliders(values, apply)
                LightMode.Scene -> SceneControls(values, apply)
                null -> Text("The lights are in different modes. Choose a mode to set them all.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Explicit On and Off, so a mixed group can be switched either way. */
@Composable
private fun PowerRow(power: Shared<Boolean>, apply: (LightEdit) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Power: " + sharedText(power) { if (it) "On" else "Off" }, Modifier.weight(1f).padding(top = 12.dp))
        if (power.valueOrNull == true) OutlinedButton(onClick = { apply(LightEdit(power = true)) }) { Text("On") }
        else Button(onClick = { apply(LightEdit(power = true)) }) { Text("On") }
        OutlinedButton(onClick = { apply(LightEdit(power = false)) }) { Text("Off") }
    }
}

@Composable
private fun ModeRow(values: TargetValues, apply: (LightEdit) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(LightMode.Cct to "White (CCT)", LightMode.Hsi to "Colour (HSI)", LightMode.Scene to "Scene").forEach { (mode, label) ->
            FilterChip(
                selected = values.mode.valueOrNull == mode,
                onClick = { apply(modeSwitch(mode, values)) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
internal fun CctSlider(values: TargetValues, apply: (LightEdit) -> Unit) {
    val range = values.cctRange
    if (range == null) {
        Text("Colour temperature: ${values.cctUnavailable}", style = MaterialTheme.typography.bodyMedium)
        return
    }
    SharedSlider("Colour temperature", values.cct, range.min..range.max, { "${it * 100} K" }, onCommit = { apply(LightEdit(cct = it)) })
}

@Composable
internal fun HsiSliders(values: TargetValues, apply: (LightEdit) -> Unit) {
    SharedSlider("Hue", values.hue.normalize(Field.Hue), Field.Hue.limits, { "${it * 5}°" }, onCommit = { apply(LightEdit(hue = it)) })
    SharedSlider("Saturation", values.saturation, 0..100, { "$it %" }, onCommit = { apply(LightEdit(saturation = it)) })
}

private fun Shared<Int>.normalize(field: Field): Shared<Int> = when (this) {
    is Shared.Same -> Shared.Same(field.normalize(value))
    Shared.Mixed -> Shared.Mixed
    Shared.Unknown -> Shared.Unknown
}

/**
 * A slider over a shared value. Mixed or Unknown is labelled as such and the thumb starts mid-range;
 * nothing is applied until the owner releases it.
 */
@Composable
internal fun SharedSlider(
    label: String,
    shared: Shared<Int>,
    range: IntRange,
    format: (Int) -> String,
    onCommit: (Int) -> Unit,
) {
    val start = shared.valueOrNull?.coerceIn(range) ?: ((range.first + range.last) / 2)
    var position by remember(shared, range) { mutableFloatStateOf(start.toFloat()) }
    var touched by remember(shared, range) { mutableStateOf(false) }
    val shown = if (touched || shared is Shared.Same) format(position.roundToInt()) else sharedText(shared, format)
    Column {
        Text("$label: $shown", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = position,
            onValueChange = { position = it; touched = true },
            onValueChangeFinished = { onCommit(position.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

internal fun <T> sharedText(shared: Shared<T>, format: (T) -> String): String = when (shared) {
    is Shared.Same -> format(shared.value)
    Shared.Mixed -> "Mixed"
    Shared.Unknown -> "Unknown"
}
