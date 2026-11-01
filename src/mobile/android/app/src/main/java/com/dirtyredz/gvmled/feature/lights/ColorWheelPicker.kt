package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.light.control.Shared

private val presets = listOf(
    "Red" to 0, "Orange" to 6, "Yellow" to 12, "Green" to 24,
    "Cyan" to 36, "Blue" to 48, "Purple" to 54, "Magenta" to 60,
)

private fun colourOf(selection: HueSat): Color =
    Color.hsv((selection.hue * ColorWheelMath.DEGREES_PER_STEP).toFloat(), selection.saturation / 100f, 1f)

/**
 * Hue/saturation wheel (angle = hue, distance = saturation) with a preview swatch and preset colours.
 * Dragging only moves the preview; one [onCommit] fires on drag end, tap or preset tap, so a drag is
 * one wanted revision. Mixed or Unknown values show no thumb until the owner picks.
 */
@Composable
internal fun ColorWheelPicker(hue: Shared<Int>, saturation: Shared<Int>, onCommit: (hue: Int, saturation: Int) -> Unit) {
    val known = if (hue is Shared.Same && saturation is Shared.Same) HueSat(hue.value, saturation.value) else null
    var picked by remember(hue, saturation) { mutableStateOf<HueSat?>(null) }
    val shown = picked ?: known
    fun commit(selection: HueSat) { picked = selection; onCommit(selection.hue, selection.saturation) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(shown?.let(::colourOf) ?: MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            val readout = shown?.let { "Hue ${it.hue * ColorWheelMath.DEGREES_PER_STEP}°  Saturation ${it.saturation} %" }
                ?: "Hue: ${sharedText(hue) { "" }}  Saturation: ${sharedText(saturation) { "" }}"
            Text(readout, style = MaterialTheme.typography.bodyMedium)
        }
        Wheel(shown, onPreview = { picked = it }, onCommit = ::commit)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (name, h) ->
                val preset = HueSat(h, 100)
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(colourOf(preset))
                        .border(if (shown == preset) 3.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .semantics { contentDescription = name }
                        .clickable { commit(preset) },
                )
            }
        }
    }
}

@Composable
private fun Wheel(shown: HueSat?, onPreview: (HueSat) -> Unit, onCommit: (HueSat) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun at(p: Offset): HueSat {
        val radius = size.width / 2f
        return ColorWheelMath.fromOffset(p.x - radius, p.y - radius, radius)
    }
    val rim = MaterialTheme.colorScheme.outline
    var last by remember { mutableStateOf<HueSat?>(null) }
    Canvas(
        Modifier.fillMaxWidth().heightIn(max = 280.dp).aspectRatio(1f)
            .semantics { contentDescription = "Colour wheel: angle sets hue, distance from centre sets saturation" }
            .pointerInput(Unit) { size = this.size; detectTapGestures { onCommit(at(it)) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { last = at(it).also(onPreview) },
                    onDrag = { change, _ -> change.consume(); last = at(change.position).also(onPreview) },
                    onDragEnd = { last?.let(onCommit) },
                )
            },
    ) {
        size = IntSize(this.size.width.toInt(), this.size.height.toInt())
        val radius = this.size.width / 2f
        val centre = Offset(radius, radius)
        val sweep = (0..6).map { Color.hsv(it * 60f, 1f, 1f) }
        drawCircle(Brush.sweepGradient(sweep, centre), radius, centre)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), centre, radius), radius, centre)
        shown?.let {
            val (dx, dy) = ColorWheelMath.toOffset(it, radius)
            val thumb = centre + Offset(dx, dy)
            drawCircle(colourOf(it), 12.dp.toPx(), thumb)
            drawCircle(Color.White, 12.dp.toPx(), thumb, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            drawCircle(Color.Black, 14.dp.toPx(), thumb, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        drawCircle(rim, radius, centre, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}
