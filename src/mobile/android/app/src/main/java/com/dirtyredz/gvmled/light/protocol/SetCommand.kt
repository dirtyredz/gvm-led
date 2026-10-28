package com.dirtyredz.gvmled.light.protocol

/** One legacy set command (`57 00 CMD 01 VAL`) in wire units, before framing. */
data class SetCommand(val command: Int, val value: Int) {
    init {
        require(command in LegacyCommand.POWER..LegacyCommand.EFFECT_TIME) { "Unknown legacy set command $command" }
        require(value in 0..255) { "Value must fit one wire byte" }
    }

    fun encode(deviceId: Int, deviceType: Int): ByteArray = LegacyPacketEncoder.set(deviceId, deviceType, command, value)
}

/** Legacy set command numbers (protocol.md, "Legacy command bytes"). Pick (01) is never sent by this app. */
object LegacyCommand {
    const val POWER = 0x00
    const val BRIGHTNESS = 0x02
    const val CCT = 0x03
    const val HUE = 0x04
    const val SATURATION = 0x05
    const val MODE = 0x06
    const val SCENE = 0x07
    const val EFFECT_TIME = 0x08
}
