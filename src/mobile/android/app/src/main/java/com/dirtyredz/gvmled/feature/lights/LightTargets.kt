package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.repository.LightEntry
import java.time.ZoneId

/** One selectable target: an individual light (by address) or a named group (by its stable ID). */
data class TargetChip(val key: String, val label: String, val isGroup: Boolean, val selected: Boolean)

/** Stable selection keys, so renaming a light or group never changes what is selected. */
object TargetKeys {
    fun light(address: String) = "light:$address"
    fun group(id: String) = "group:$id"
}

/**
 * The selection to show: [selected] while that light or group still exists, otherwise the first
 * light (a deleted group falls back to an individual light), or null when there are no lights.
 * No implicit "Both"/"All" target exists.
 */
fun resolveTarget(selected: String?, catalog: LightCatalog): String? {
    val keys = catalog.lights.map { TargetKeys.light(it.address) } + catalog.groups.map { TargetKeys.group(it.id) }
    return selected?.takeIf { it in keys } ?: catalog.lights.firstOrNull()?.let { TargetKeys.light(it.address) }
}

/** Read-only Lights state: the target chips and the cards of the selected target's members. */
fun lightsState(
    radio: RadioState,
    entries: List<LightEntry>,
    catalog: LightCatalog,
    selected: String?,
    zone: ZoneId,
): LightsUiState {
    val key = resolveTarget(selected, catalog)
    val group = catalog.groups.firstOrNull { TargetKeys.group(it.id) == key }
    val members: Set<String> = when {
        group != null -> group.members.toSet()
        key != null -> setOf(key.removePrefix("light:"))
        else -> emptySet()
    }
    val chips = catalog.lights.map { TargetChip(TargetKeys.light(it.address), it.name, isGroup = false, TargetKeys.light(it.address) == key) } +
        catalog.groups.map { TargetChip(TargetKeys.group(it.id), it.name, isGroup = true, TargetKeys.group(it.id) == key) }
    return LightsUiState(
        radio = radio,
        lights = entries.filter { it.connection.address in members }.map { it.toCardModel(zone) },
        targets = chips,
        groupSummary = group?.let { "${it.name}: ${it.members.size} light" + if (it.members.size == 1) "" else "s" },
        isEmpty = catalog.lights.isEmpty(),
    )
}
