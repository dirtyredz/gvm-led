package com.dirtyredz.gvmled.protocol

sealed interface LegacyMessage {
    val deviceId: Int
    val deviceType: Int
}

@ConsistentCopyVisibility
data class HelloReply private constructor(
    override val deviceId: Int,
    override val deviceType: Int,
    val minCctKelvin: Int,
    val maxCctKelvin: Int,
) : LegacyMessage {
    companion object {
        internal fun decode(deviceId: Int, deviceType: Int, payload: List<Int>): HelloReply? {
            if (payload.size != 5 || payload.subList(0, 3) != listOf(0, 0, 2)) return null
            val minCct = payload[3]
            val maxCct = payload[4]
            if (minCct == 0 || minCct > maxCct) return null
            return HelloReply(deviceId, deviceType, minCct * 100, maxCct * 100)
        }
    }
}

/** Deliberately not a full report: no power, mode, HSI or scene confirmation. */
@ConsistentCopyVisibility
data class ShortStateReply private constructor(
    override val deviceId: Int,
    override val deviceType: Int,
    val pick: Int,
    val brightness: Int,
    val cct: Int,
) : LegacyMessage {
    companion object {
        internal fun decode(deviceId: Int, deviceType: Int, payload: List<Int>): ShortStateReply? {
            if (payload.size != 6 || payload.subList(0, 3) != listOf(0, 0, 1)) return null
            val pick = payload[3]
            val brightness = payload[4]
            val cct = payload[5]
            if (pick !in 1..12 || brightness !in 0..100 || cct == 0) return null
            return ShortStateReply(deviceId, deviceType, pick, brightness, cct)
        }
    }
}

/**
 * A validated full state report in wire units. Construction is restricted to message parsing:
 * every field is named and range-checked in one place, and the acknowledgement payload derives
 * from the named fields (each is one wire byte, so the echo is exact).
 */
@ConsistentCopyVisibility
data class FullStateReport private constructor(
    override val deviceId: Int,
    override val deviceType: Int,
    /** 0 off, 1 on. */
    val power: Int,
    /** Group/channel number, 1-12. */
    val pick: Int,
    /** Percent, 0-100 in 1 % steps. */
    val brightness: Int,
    /** Colour temperature in 100 K units; reported even when CCT is not the active mode. */
    val cct: Int,
    /** Hue in 5-degree units, 0-72. */
    val hue: Int,
    /** Percent, 0-100. */
    val saturation: Int,
    /** 1 CCT, 2 HSI, 3 scenes. */
    val mode: Int,
    /** Scene number 1-20; may be 0 unless scenes are active. */
    val scene: Int,
    /** Effect time in 100 ms units, 0-50. */
    val effectTime: Int,
) : LegacyMessage {
    /** The nine payload bytes in wire order; an acknowledgement must echo them exactly. */
    internal fun wirePayload(): ByteArray =
        intArrayOf(power, pick, brightness, cct, hue, saturation, mode, scene, effectTime)
            .map(Int::toByte).toByteArray()

    private fun isValid(): Boolean =
        power in 0..1 && pick in 1..12 && brightness in 0..100 && cct in 1..255 &&
            hue in 0..72 && saturation in 0..100 && mode in 1..3 && scene in 0..20 &&
            effectTime in 0..50 && !(mode == 3 && scene == 0)

    companion object {
        // Only called after framing and CRC validation. Inactive scene/time may be zero.
        internal fun decode(deviceId: Int, deviceType: Int, payload: List<Int>): FullStateReport? {
            if (payload.size != 9) return null
            return FullStateReport(
                deviceId, deviceType,
                power = payload[0], pick = payload[1], brightness = payload[2], cct = payload[3],
                hue = payload[4], saturation = payload[5], mode = payload[6], scene = payload[7],
                effectTime = payload[8],
            ).takeIf { it.isValid() }
        }
    }
}
