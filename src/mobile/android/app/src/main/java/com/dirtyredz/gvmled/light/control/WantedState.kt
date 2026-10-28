package com.dirtyredz.gvmled.light.control

import com.dirtyredz.gvmled.light.protocol.FullStateReport
import com.dirtyredz.gvmled.light.protocol.HelloReply
import com.dirtyredz.gvmled.light.protocol.SceneCatalog

/** The light's three control modes, with their wire values (command 06 and the report's mode byte). */
enum class LightMode(val wire: Int) {
    Cct(1), Hsi(2), Scene(3);

    companion object {
        fun fromWire(value: Int): LightMode? = entries.firstOrNull { it.wire == value }
    }
}

/** A light's supported colour temperatures from its hello reply, in 100 K wire units, inclusive. */
data class CctRange(val min: Int, val max: Int) {
    operator fun contains(cct: Int): Boolean = cct in min..max

    override fun toString() = "${min * 100}-${max * 100} K"

    companion object {
        fun of(hello: HelloReply): CctRange = CctRange(hello.minCctKelvin / 100, hello.maxCctKelvin / 100)

        /**
         * Greatest minimum through smallest maximum. Null when any range is unknown or they share no
         * value: a combined CCT control must never be offered on a guess.
         */
        fun intersection(ranges: Collection<CctRange?>): CctRange? {
            if (ranges.isEmpty() || ranges.any { it == null }) return null
            val known = ranges.filterNotNull()
            val min = known.maxOf { it.min }
            val max = known.minOf { it.max }
            return if (min <= max) CctRange(min, max) else null
        }
    }
}

/**
 * What the app wants one light to show, in wire units: brightness and saturation 0-100, hue 0-71
 * (5-degree steps; a reported 72/360 degrees normalizes to 0), CCT in 100 K steps, scene 1-20,
 * effect time 1-50 (x100 ms). Every mode keeps its
 * own values, so switching mode and back restores them. A null value is unknown, never a default.
 */
data class WantedState(
    val power: Boolean,
    val mode: LightMode,
    val brightness: Int,
    val cct: Int? = null,
    val hue: Int? = null,
    val saturation: Int? = null,
    val scene: Int? = null,
    val effectTime: Int? = null,
) {
    companion object {
        /**
         * The first valid full report as the starting intent (no stored intent yet). Fields that are
         * placeholders rather than settings stay unknown: scene 0, effect time 0, and a CCT outside
         * the light's hello range.
         */
        fun fromReport(report: FullStateReport, range: CctRange): WantedState? {
            val mode = LightMode.fromWire(report.mode) ?: return null
            return WantedState(
                power = report.power == 1,
                mode = mode,
                brightness = report.brightness,
                cct = report.cct.takeIf { it in range },
                hue = Field.Hue.read(report),
                saturation = report.saturation,
                scene = report.scene.takeIf(SceneCatalog::isSupported),
                effectTime = report.effectTime.takeIf { it in SceneCatalog.EFFECT_TIMES },
            )
        }
    }
}

/** A light's wanted state with its own monotonic revision; a newer revision supersedes an older one. */
data class WantedRecord(val revision: Long, val state: WantedState)
