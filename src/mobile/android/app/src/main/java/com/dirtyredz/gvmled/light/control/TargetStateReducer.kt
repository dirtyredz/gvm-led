package com.dirtyredz.gvmled.light.control

/** One control value across a target's members. */
sealed interface Shared<out T> {
    /** Every member has this value. */
    data class Same<T>(val value: T) : Shared<T>

    /** At least two members have different known values. Never shown as an average or one member's value. */
    data object Mixed : Shared<Nothing>

    /** Some member's value is unknown, so agreement cannot be established. */
    data object Unknown : Shared<Nothing>

    val valueOrNull: T? get() = (this as? Same<T>)?.value
}

/** The wanted values of a target, field by field, plus what limits its CCT control. */
data class TargetValues(
    val power: Shared<Boolean>,
    val mode: Shared<LightMode>,
    val brightness: Shared<Int>,
    val cct: Shared<Int>,
    val hue: Shared<Int>,
    val saturation: Shared<Int>,
    val scene: Shared<Int>,
    val effectTime: Shared<Int>,
    /** The members' common CCT range, or null with [cctUnavailable] saying why. */
    val cctRange: CctRange?,
    val cctUnavailable: String?,
    /** Members with no wanted state yet; every edit is blocked until they have one. */
    val withoutSettings: List<String>,
)

/** Pure reduction of per-member wanted states and hello ranges into one target's control values. */
object TargetStateReducer {
    fun reduce(
        members: List<String>,
        wanted: Map<String, WantedState>,
        ranges: Map<String, CctRange?>,
        name: (String) -> String,
    ): TargetValues {
        val states = members.map { wanted[it] }
        val missingRange = members.firstOrNull { ranges[it] == null }
        val range = CctRange.intersection(members.map { ranges[it] })
        return TargetValues(
            power = shared(states) { it.power },
            mode = shared(states) { it.mode },
            brightness = shared(states) { it.brightness },
            cct = shared(states) { it.cct },
            hue = shared(states) { it.hue },
            saturation = shared(states) { it.saturation },
            scene = shared(states) { it.scene },
            effectTime = shared(states) { it.effectTime },
            cctRange = range,
            cctUnavailable = when {
                members.isEmpty() -> "No lights are selected"
                missingRange != null -> "${name(missingRange)} has not reported its colour temperature range yet"
                range == null -> "The selected lights share no colour temperature"
                else -> null
            },
            withoutSettings = members.filter { wanted[it] == null },
        )
    }

    private fun <T> shared(states: List<WantedState?>, read: (WantedState) -> T?): Shared<T> {
        val values = states.map { it?.let(read) }
        val known = values.filterNotNull().distinct()
        return when {
            known.size > 1 -> Shared.Mixed
            values.isEmpty() || values.any { it == null } -> Shared.Unknown
            else -> Shared.Same(known.single())
        }
    }
}
