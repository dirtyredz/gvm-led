package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.repository.LightEntry
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.light.control.CctRange
import com.dirtyredz.gvmled.light.control.TargetStateReducer
import java.time.ZoneId

/** One selectable target: an individual light (by address) or a named group (by its stable ID). */
data class TargetChip(val target: ControlTarget, val label: String, val selected: Boolean) {
    val isGroup: Boolean get() = target is ControlTarget.Group
}

/**
 * Lights state: the target chips, the selected target's wanted values (as one, Mixed or Unknown),
 * the per-member apply outcome and the cards of the selected target's members.
 */
fun lightsState(
    radio: RadioState,
    entries: List<LightEntry>,
    catalog: LightCatalog,
    selected: ControlTarget?,
    zone: ZoneId,
    message: String? = null,
    settingsProblem: String? = null,
): LightsUiState {
    val target = ControlTarget.resolve(selected, catalog)
    val group = (target as? ControlTarget.Group)?.let { catalog.group(it.id) }
    val members = target?.members(catalog).orEmpty()
    val byAddress = entries.associateBy { it.connection.address }
    val memberEntries = members.mapNotNull { byAddress[it] }
    val chips = catalog.lights.map { ControlTarget.Light(it.address).let { t -> TargetChip(t, it.name, t == target) } } +
        catalog.groups.map { ControlTarget.Group(it.id).let { t -> TargetChip(t, it.name, t == target) } }
    val controls = target?.let {
        TargetStateReducer.reduce(
            members = members,
            wanted = memberEntries.mapNotNull { entry -> entry.wanted?.let { entry.connection.address to it.state } }.toMap(),
            ranges = memberEntries.associate { entry -> entry.connection.address to entry.connection.currentHello?.let(CctRange::of) },
            name = { address -> catalog.light(address)?.name ?: address },
        )
    }
    return LightsUiState(
        radio = radio,
        lights = memberEntries.map { it.toCardModel(zone) },
        targets = chips,
        groupSummary = group?.let { "${it.name}: ${it.members.size} light" + if (it.members.size == 1) "" else "s" },
        isEmpty = catalog.lights.isEmpty(),
        target = target,
        controls = controls,
        outcome = outcomeText(memberEntries),
        message = message,
        settingsProblem = settingsProblem,
    )
}
