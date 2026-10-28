package com.dirtyredz.gvmled.light.control

/**
 * One owner action: only the fields set here change; every other per-light setting is preserved.
 * Fields listed in [fillOnly] supply a value only to a member that has none (for example the slider
 * values offered when switching a group to HSI); they never overwrite a member's known value.
 */
data class LightEdit(
    val power: Boolean? = null,
    val mode: LightMode? = null,
    val brightness: Int? = null,
    val cct: Int? = null,
    val hue: Int? = null,
    val saturation: Int? = null,
    val scene: Int? = null,
    val effectTime: Int? = null,
    val fillOnly: Set<Field> = emptySet(),
) {
    fun applyTo(base: WantedState): WantedState = base.copy(
        power = power ?: base.power,
        mode = mode ?: base.mode,
        brightness = brightness ?: base.brightness,
        cct = pick(Field.Cct, cct, base.cct),
        hue = pick(Field.Hue, hue, base.hue),
        saturation = pick(Field.Saturation, saturation, base.saturation),
        scene = pick(Field.Scene, scene, base.scene),
        effectTime = pick(Field.EffectTime, effectTime, base.effectTime),
    )

    private fun pick(field: Field, edited: Int?, current: Int?): Int? = when {
        edited == null -> current
        field in fillOnly -> current ?: edited
        else -> edited
    }
}

/** The outcome of expanding an edit over a target's members: all members accepted, or none. */
sealed interface EditPlan {
    /** The next wanted state of every member, in member order. */
    data class Accepted(val states: Map<String, WantedState>) : EditPlan
    data class Rejected(val reason: String) : EditPlan
}

/**
 * Expands one edit over a snapshot of member addresses. Each member's own wanted state is patched
 * with only the edited fields, and every result is validated before any is accepted: one member that
 * lacks a baseline, a required value or a capability rejects the whole edit with a reason that names
 * it. Nothing is silently skipped, clamped per light or defaulted.
 */
object TargetEditPlanner {
    fun expand(
        members: List<String>,
        wanted: Map<String, WantedState>,
        ranges: Map<String, CctRange?>,
        name: (String) -> String,
        edit: LightEdit,
    ): EditPlan {
        if (members.isEmpty()) return EditPlan.Rejected("No lights are selected")
        val next = LinkedHashMap<String, WantedState>()
        for (address in members.distinct()) {
            val base = wanted[address]
                ?: return EditPlan.Rejected("${name(address)} has no settings yet; wait until it has connected once")
            val state = edit.applyTo(base)
            val range = ranges[address]
            val cct = state.cct
            if (cct != null && cct != base.cct) {
                if (range == null) return EditPlan.Rejected("${name(address)} has not reported its colour temperature range yet")
                if (cct !in range) return EditPlan.Rejected("${name(address)} supports only $range")
            }
            ModeCommandPlanner.problem(state, range)?.let { return EditPlan.Rejected("${name(address)} $it") }
            next[address] = state
        }
        return EditPlan.Accepted(next)
    }
}
