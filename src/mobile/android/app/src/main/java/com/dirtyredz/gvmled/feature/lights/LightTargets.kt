package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.repository.LightEntry
import java.time.ZoneId

/** One selectable target: an individual light (by address) or a named group (by its stable ID). */
data class TargetChip(val target: ControlTarget, val label: String, val selected: Boolean) {
    val isGroup: Boolean get() = target is ControlTarget.Group
}

/** Read-only Lights state: the target chips and the cards of the selected target's members. */
fun lightsState(
    radio: RadioState,
    entries: List<LightEntry>,
    catalog: LightCatalog,
    selected: ControlTarget?,
    zone: ZoneId,
): LightsUiState {
    val target = ControlTarget.resolve(selected, catalog)
    val group = (target as? ControlTarget.Group)?.let { catalog.group(it.id) }
    val members = target?.members(catalog).orEmpty().toSet()
    val chips = catalog.lights.map { ControlTarget.Light(it.address).let { t -> TargetChip(t, it.name, t == target) } } +
        catalog.groups.map { ControlTarget.Group(it.id).let { t -> TargetChip(t, it.name, t == target) } }
    return LightsUiState(
        radio = radio,
        lights = entries.filter { it.connection.address in members }.map { it.toCardModel(zone) },
        targets = chips,
        groupSummary = group?.let { "${it.name}: ${it.members.size} light" + if (it.members.size == 1) "" else "s" },
        isEmpty = catalog.lights.isEmpty(),
    )
}
