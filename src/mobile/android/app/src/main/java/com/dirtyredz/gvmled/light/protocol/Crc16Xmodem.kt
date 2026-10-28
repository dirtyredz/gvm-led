package com.dirtyredz.gvmled.light.protocol

/** CRC-16/XMODEM: polynomial 0x1021, initial zero, no reflection or final XOR. */
object Crc16Xmodem {
    fun calculate(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
        }
        return crc
    }
}
