package com.dirtyredz.gvmled.protocol

import org.junit.Assert.*
import org.junit.Test

class LegacyFrameDecoderTest {
    private val hello = hex("4C 54 0A 00 33 53 00 00 02 20 38 F3 9B")
    // Real RX, live-test-20261026-001355.log (both lights).
    private val report = hex("4C 54 0E 00 33 03 01 0B 07 2C 47 64 02 01 01 86 9C")
    private val second = hex("4C 54 0E 00 33 03 01 0B 22 24 30 64 02 01 01 E6 DD")

    @Test fun helloCapabilities() {
        val message = LegacyFrameDecoder().feed(hello).single() as HelloReply
        assertEquals(0, message.deviceId)
        assertEquals(0x33, message.deviceType)
        assertEquals(3200, message.minCctKelvin)
        assertEquals(5600, message.maxCctKelvin)
    }

    @Test fun fullReportFieldsAndExactAcknowledgementPayload() {
        val message = LegacyFrameDecoder().feed(report).single() as FullStateReport
        assertEquals(listOf(1, 11, 7, 44, 71, 100, 2, 1, 1), listOf(
            message.power, message.pick, message.brightness, message.cct, message.hue,
            message.saturation, message.mode, message.scene, message.effectTime,
        ))
        val ack = LegacyPacketEncoder.acknowledge(message)
        assertEquals(0x48, ack[5].toInt())
        assertArrayEquals(report.copyOfRange(6, 15), ack.copyOfRange(6, 15))
        assertEquals(14, ack[2].toInt())
        assertEquals(0xff, ack.last().toInt() and 255)
        // CRC independently checked by the standard XMODEM zero-residue property.
        assertEquals(0, Crc16Xmodem.calculate(ack.copyOf(ack.size - 1)))
        assertFalse(report.copyOfRange(15, 17).contentEquals(ack.copyOfRange(15, 17)))
    }

    @Test fun everyFragmentBoundaryAndCoalescing() {
        for (frame in listOf(hello, report, second)) for (split in 1 until frame.size) {
            val decoder = LegacyFrameDecoder()
            assertTrue(decoder.feed(frame.copyOfRange(0, split)).isEmpty())
            assertEquals(1, decoder.feed(frame.copyOfRange(split, frame.size)).size)
        }
        val decoder = LegacyFrameDecoder()
        assertEquals(3, decoder.feed(hello + byteArrayOf(-1) + report + second).size)
        assertEquals(0, decoder.bufferedByteCount)
        assertEquals(1, (hello.indices).flatMap { decoder.feed(byteArrayOf(hello[it])) }.size)
    }

    @Test fun rejectsEverySingleByteCorruptionAndRecovers() {
        for (frame in listOf(hello, report)) for (index in frame.indices) {
            val corrupt = frame.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            val decoder = LegacyFrameDecoder()
            assertTrue(decoder.feed(corrupt).isEmpty())
            assertEquals(1, decoder.feed(hello).size)
        }
    }

    @Test fun boundedNoiseMalformedLengthsAndTruncatedFrameRecovery() {
        val decoder = LegacyFrameDecoder()
        assertTrue(decoder.feed(ByteArray(100_000) { 0x4c }).isEmpty())
        assertTrue(decoder.bufferedByteCount <= LegacyFrameDecoder.MAX_FRAME_SIZE)
        assertEquals(1, decoder.feed(hex("4C 54 FF 4C 54 00") + hello).size)
        assertEquals(1, decoder.feed(report.copyOf(8) + hello).size)
        decoder.feed(report.copyOf(8))
        decoder.reset()
        assertEquals(0, decoder.bufferedByteCount)
        assertEquals(1, decoder.feed(hello).size)
    }

    // Synthetic structural cases use a test-only independent bitwise CRC, never the encoder.
    private fun frame(body: String): ByteArray {
        val bytes = hex(body)
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor (if (crc and 0x8000 != 0) 0x1021 else 0)) and 65535 }
        }
        return bytes + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    @Test fun shortReplyIsADistinctType() {
        val reply = LegacyFrameDecoder().feed(frame("4C 54 0B 00 33 02 00 00 01 0B 07 2C")).single()
        assertTrue(reply is ShortStateReply)
        reply as ShortStateReply
        assertEquals(11, reply.pick)
        assertEquals(7, reply.brightness)
        assertEquals(44, reply.cct)
    }

    @Test fun validCrcDoesNotMakeMalformedMessagesValid() {
        val malformed = listOf(
            "4C 54 0A 00 33 53 01 00 02 20 38", // hello marker
            "4C 54 0A 00 33 53 00 00 02 38 20", // inverted bounds
            "4C 54 0A 00 33 53 00 00 02 00 38", // zero bound
            "4C 54 0B 00 33 02 00 00 02 0B 07 2C", // short marker
            "4C 54 0E 00 33 03 02 0B 07 2C 47 64 02 01 01", // power
            "4C 54 0E 00 33 03 01 0B 65 2C 47 64 02 01 01", // brightness
            "4C 54 0E 00 33 03 01 0B 07 2C 49 64 02 01 01", // hue
            "4C 54 0E 00 33 03 01 0B 07 2C 47 65 02 01 01", // saturation
            "4C 54 0E 00 33 03 01 0B 07 2C 47 64 04 01 01", // mode
            "4C 54 0A 00 33 03 00 00 02 20 38", // kind/length mismatch
        )
        for (body in malformed) {
            assertEquals(1, LegacyFrameDecoder().feed(frame(body) + hello).size)
        }
    }
}
