package com.dirtyredz.gvmled.light.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMessageParserTest {
    @Test fun parsesHelloReplyVector() {
        val message = LegacyMessageParser.parse(
            hex("4C 54 0A 00 33 53 00 00 02 20 38 F3 9B"),
        )

        assertTrue(message is HelloReply)
        message as HelloReply
        assertEquals(0, message.deviceId)
        assertEquals(0x33, message.deviceType)
        assertEquals(3200, message.minCctKelvin)
        assertEquals(5600, message.maxCctKelvin)
    }

    @Test fun parsesFullStateReportVector() {
        val message = LegacyMessageParser.parse(
            hex("4C 54 0E 00 33 03 01 0B 07 2C 47 64 02 01 01 86 9C"),
        )

        assertTrue(message is FullStateReport)
        message as FullStateReport
        assertEquals(listOf(1, 11, 7, 44, 71, 100, 2, 1, 1), listOf(
            message.power, message.pick, message.brightness, message.cct, message.hue,
            message.saturation, message.mode, message.scene, message.effectTime,
        ))
    }

    @Test fun parsesShortStateReplyVector() {
        val message = LegacyMessageParser.parse(
            hex("4C 54 0B 00 33 02 00 00 01 0B 07 2C 64 61"),
        )

        assertTrue(message is ShortStateReply)
        message as ShortStateReply
        assertEquals(11, message.pick)
        assertEquals(7, message.brightness)
        assertEquals(44, message.cct)
    }
}
