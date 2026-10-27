package com.dirtyredz.gvmled.protocol

/** Parses a complete frame after the stream decoder has verified its CRC. */
internal object LegacyMessageParser {
    fun parse(frameBytes: ByteArray): LegacyMessage? {
        if (frameBytes.size < LegacyFrame.PREFIX_SIZE) return null
        val values = frameBytes.map { it.toInt() and 0xff }
        if (values[0] != LegacyFrame.HEADER_FIRST || values[1] != LegacyFrame.HEADER_SECOND) return null

        val length = values[2]
        if (length !in LegacyFrame.REPLY_LENGTHS || frameBytes.size != LegacyFrame.frameSize(length)) return null

        val deviceId = values[3]
        val deviceType = values[4]
        val kind = values[5]
        val payload = values.subList(6, values.size - LegacyFrame.CRC_SIZE)
        return when {
            length == LegacyFrame.HELLO_REPLY_LENGTH && kind == 0x53 ->
                HelloReply.decode(deviceId, deviceType, payload)
            length == LegacyFrame.SHORT_STATE_REPLY_LENGTH && kind == 0x02 ->
                ShortStateReply.decode(deviceId, deviceType, payload)
            length == LegacyFrame.FULL_STATE_REPORT_LENGTH && kind == 0x03 ->
                FullStateReport.decode(deviceId, deviceType, payload)
            else -> null
        }
    }
}
