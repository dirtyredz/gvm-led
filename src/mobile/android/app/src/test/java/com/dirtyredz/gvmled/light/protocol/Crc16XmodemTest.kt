package com.dirtyredz.gvmled.light.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

internal fun hex(text: String): ByteArray = text.split(" ").map { it.toInt(16).toByte() }.toByteArray()

class Crc16XmodemTest {
    @Test fun knownVectors() {
        assertEquals(0, Crc16Xmodem.calculate(byteArrayOf()))
        assertEquals(0x31c3, Crc16Xmodem.calculate("123456789".toByteArray()))
        assertEquals(0x9474, Crc16Xmodem.calculate(hex("4C 54 09 00 00 53 00 00 01 00")))
    }
}
