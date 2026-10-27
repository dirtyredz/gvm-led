package com.dirtyredz.gvmled.protocol

sealed interface LegacyMessage {
    val deviceId: Int
    val deviceType: Int
}

data class HelloReply internal constructor(
    override val deviceId: Int,
    override val deviceType: Int,
    val minCctKelvin: Int,
    val maxCctKelvin: Int,
) : LegacyMessage

/** Deliberately not a full report: no power, mode, HSI or scene confirmation. */
data class ShortStateReply internal constructor(
    override val deviceId: Int,
    override val deviceType: Int,
    val pick: Int,
    val brightness: Int,
    val cct: Int,
) : LegacyMessage

/** Immutable validated wire values. Construction is restricted to the frame decoder. */
class FullStateReport private constructor(
    override val deviceId: Int,
    override val deviceType: Int,
    private val payload: List<Int>,
) : LegacyMessage {
    val power: Int get() = payload[0]
    val pick: Int get() = payload[1]
    val brightness: Int get() = payload[2]
    val cct: Int get() = payload[3]
    val hue: Int get() = payload[4]
    val saturation: Int get() = payload[5]
    val mode: Int get() = payload[6]
    val scene: Int get() = payload[7]
    val effectTime: Int get() = payload[8]

    internal fun wirePayload(): ByteArray = payload.map(Int::toByte).toByteArray()

    companion object {
        // Only called after framing and CRC validation. Inactive scene/time may be zero.
        internal fun decode(deviceId: Int, deviceType: Int, payload: List<Int>): FullStateReport? {
            if (payload.size != 9) return null
            val (power, pick, brightness, cct, hue) = payload
            if (power !in 0..1 || pick !in 1..12 || brightness !in 0..100 || cct !in 1..255 ||
                hue !in 0..72 || payload[5] !in 0..100 || payload[6] !in 1..3 ||
                payload[7] !in 0..20 || payload[8] !in 0..50) return null
            if (payload[6] == 3 && payload[7] == 0) return null
            return FullStateReport(deviceId, deviceType, payload.toList())
        }
    }
}
