package com.dirtyredz.gvmled.feature.lights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.dirtyredz.gvmled.data.model.ControlTarget

/** Every individual light, then every named group. There is no implicit "Both"/"All" target. */
@Composable
internal fun TargetSelector(targets: List<TargetChip>, onSelect: (ControlTarget) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        targets.forEach { target ->
            FilterChip(
                selected = target.selected,
                onClick = { onSelect(target.target) },
                label = { Text(if (target.isGroup) "${target.label} (group)" else target.label) },
            )
        }
    }
}
