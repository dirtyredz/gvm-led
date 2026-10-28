package com.dirtyredz.gvmled.feature.lights

import com.dirtyredz.gvmled.light.connection.LightConnection
import com.dirtyredz.gvmled.light.connection.LinkStatus
import com.dirtyredz.gvmled.light.connection.Vectors
import com.dirtyredz.gvmled.data.repository.LightEntry
import com.dirtyredz.gvmled.light.protocol.FullStateReport
import com.dirtyredz.gvmled.light.protocol.HelloReply
import com.dirtyredz.gvmled.light.protocol.LegacyFrameDecoder
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightsUiStateTest {
    private val hello = LegacyFrameDecoder().feed(Vectors.HELLO_REPLY).single() as HelloReply

    private fun report(bytes: ByteArray) = LegacyFrameDecoder().feed(bytes).single() as FullStateReport

    private fun card(connection: LightConnection, name: String = "Light 1") =
        LightEntry(connection, name).toCardModel(ZoneOffset.UTC)

    private fun live(report: FullStateReport) = LightConnection(
        address = "94:C9:60:E5:D7:6F", status = LinkStatus.Live, generation = 1, hello = hello,
        report = report, reportGeneration = 1, reportAtMillis = 3_723_000L,
    )

    @Test fun hsiReportInDisplayUnits() {
        val model = card(live(report(Vectors.REPORT_1)))

        assertEquals("D7:6F", model.addressSuffix)
        assertEquals("Live", model.status)
        assertTrue(model.isLive)
        assertFalse(model.isStale)
        assertEquals("3200-5600 K", model.cctRange)
        val observed = model.observed!!
        assertEquals("On", observed.power)
        assertEquals("HSI", observed.mode)
        assertEquals("Hue 355°, saturation 100 %", observed.colour)
        assertEquals("7 %", observed.brightness)
        assertEquals("Updated 01:02:03", observed.updated)
    }

    @Test fun cctAndSceneReportsShowTheirActiveFields() {
        val cct = report(Vectors.framed("4C 54 0E 00 33 03 00 0B 32 2C 47 64 01 01 01"))
        val cctObserved = card(live(cct)).observed!!
        assertEquals(listOf("Off", "CCT", "Colour temperature", "4400 K", "50 %"),
            listOf(cctObserved.power, cctObserved.mode, cctObserved.colourLabel, cctObserved.colour, cctObserved.brightness))

        val scene = report(Vectors.framed("4C 54 0E 00 33 03 01 0B 32 2C 47 64 03 13 05"))
        assertEquals("Scene 19, effect time 500 ms", card(live(scene)).observed!!.colour)
    }

    @Test fun reportFromAnEndedLinkIsShownAsStale() {
        val waiting = live(report(Vectors.REPORT_1)).copy(status = LinkStatus.Waiting, lastError = "Disconnected (GATT status 8)")
        val model = card(waiting)
        assertTrue(model.isStale)
        assertEquals("Retrying soon: Disconnected (GATT status 8)", model.status)

        val reconnected = live(report(Vectors.REPORT_1)).copy(generation = 2)
        assertTrue(card(reconnected).isStale)
    }

    @Test fun noReportYet() {
        val model = card(LightConnection("94:C9:60:EB:67:E6", status = LinkStatus.Searching), name = "Key light")
        assertNull(model.observed)
        assertFalse(model.isStale)
        assertEquals("Key light", model.name)
        assertEquals("67:E6", model.addressSuffix)
        assertEquals("Searching", model.status)
    }
}
