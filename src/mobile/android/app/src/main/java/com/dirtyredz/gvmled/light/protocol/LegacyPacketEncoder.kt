package com.dirtyredz.gvmled.light.protocol

/** Wire framing only. Mode-specific ranges/order belong to the future control planner. */
object LegacyPacketEncoder {
    fun hello(): ByteArray = frame(0, 0, byteArrayOf(0x53, 0, 0, 1, 0))

    fun set(deviceId: Int, deviceType: Int, command: Int, value: Int): ByteArray {
        require(command in 0..8) { "Unknown legacy set command" }
        require(value in 0..255) { "Value must fit one wire byte" }
        return frame(deviceId, deviceType, byteArrayOf(0x57, 0, command.toByte(), 1, value.toByte()))
    }

    fun query(deviceId: Int, deviceType: Int): ByteArray = frame(
        deviceId, deviceType, byteArrayOf(0x52, 0, 0, if (deviceType == 0x30) 5 else 4),
    )

    fun acknowledge(report: FullStateReport): ByteArray = frame(
        report.deviceId, report.deviceType, byteArrayOf(0x48) + report.wirePayload(),
    )

    private fun frame(deviceId: Int, deviceType: Int, body: ByteArray): ByteArray {
        require(deviceId in 0..255 && deviceType in 0..255) { "Device identity must fit wire bytes" }
        val bytes = byteArrayOf(
            LegacyFrame.HEADER_FIRST.toByte(),
            LegacyFrame.HEADER_SECOND.toByte(),
            LegacyFrame.lengthForBody(body.size).toByte(),
            deviceId.toByte(),
            deviceType.toByte(),
        ) + body
        val crc = Crc16Xmodem.calculate(bytes)
        return bytes + byteArrayOf((crc ushr 8).toByte(), crc.toByte(), LegacyFrame.TRAILER.toByte())
    }
}
