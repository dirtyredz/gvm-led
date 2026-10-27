package com.dirtyredz.gvmled.protocol

import org.junit.Assert.*
import org.junit.Test

class LegacyPacketEncoderTest {
    @Test fun helloIncludesBigEndianCrcAndTerminator() {
        assertArrayEquals(hex("4C 54 09 00 00 53 00 00 01 00 94 74 FF"), LegacyPacketEncoder.hello())
    }

    // Frozen Python DRY RUN TX vectors, live-test-20261026-001349.log; type 30 is a placeholder.
    @Test fun setVectors() {
        val vectors = listOf(
            Triple(0, 1, "22 DF"), Triple(6, 2, "A0 1C"), Triple(4, 0, "EE 3E"),
            Triple(5, 100, "F5 2C"), Triple(2, 50, "4A 8F"), Triple(4, 48, "D8 6D"),
        )
        for ((command, value, crc) in vectors) {
            val expected = hex("4C 54 09 00 30 57 00 %02X 01 %02X $crc FF".format(command, value))
            assertArrayEquals(expected, LegacyPacketEncoder.set(0, 0x30, command, value))
        }
    }

    @Test fun queryDependsOnDeviceType() {
        assertArrayEquals(hex("4C 54 08 00 30 52 00 00 05 79 75 FF"), LegacyPacketEncoder.query(0, 0x30))
        assertArrayEquals(hex("4C 54 08 00 33 52 00 00 04 87 86 FF"), LegacyPacketEncoder.query(0, 0x33))
    }

    @Test fun invalidWireArgumentsAreRejected() {
        for (value in listOf(-1, 256)) {
            assertThrows(IllegalArgumentException::class.java) { LegacyPacketEncoder.set(0, 0x33, 2, value) }
            assertThrows(IllegalArgumentException::class.java) { LegacyPacketEncoder.query(value, 0x33) }
        }
        assertThrows(IllegalArgumentException::class.java) { LegacyPacketEncoder.set(0, 0x33, 9, 0) }
    }
}
