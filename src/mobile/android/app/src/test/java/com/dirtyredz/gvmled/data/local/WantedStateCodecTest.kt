package com.dirtyredz.gvmled.data.local

import com.dirtyredz.gvmled.light.control.LightMode
import com.dirtyredz.gvmled.light.control.WantedRecord
import com.dirtyredz.gvmled.light.control.WantedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class WantedStateCodecTest {
    private val a = "94:C9:60:E5:D7:6F"
    private val b = "94:C9:60:EB:67:E6"

    /** One light record as JSON text; [over] replaces raw values, [drop] removes a key, [extra] is appended raw. */
    private fun light(
        address: String = a,
        over: Map<String, String> = emptyMap(),
        drop: String? = null,
        extra: String = "",
    ): String {
        val fields = linkedMapOf(
            "address" to "\"$address\"", "revision" to "1", "power" to "true", "mode" to "2", "brightness" to "50",
            "cct" to "null", "hue" to "48", "saturation" to "100", "scene" to "null", "effectTime" to "null",
        )
        fields.putAll(over)
        drop?.let { fields.remove(it) }
        return fields.entries.joinToString(",", "{", "$extra}") { "\"${it.key}\":${it.value}" }
    }

    private fun doc(vararg lights: String, version: Int = 1) = "{\"version\":$version,\"lights\":[${lights.joinToString(",")}]}"

    private fun assertCorrupt(text: String) {
        try {
            WantedStateCodec.decode(text)
            fail("expected WantedStateFormatException for $text")
        } catch (expected: WantedStateFormatException) {
            assertNotNull(expected.message)
        }
    }

    @Test fun roundTripPreservesRevisionsAndNulls() {
        val records = linkedMapOf(
            a to WantedRecord(1, WantedState(true, LightMode.Cct, 50)),
            b to WantedRecord(7, WantedState(false, LightMode.Scene, 30, 56, 0, 20, 19, 20)),
        )
        val decoded = WantedStateCodec.decode(WantedStateCodec.encode(records))
        assertEquals(records, decoded)
        assertEquals(listOf(a, b), decoded.keys.toList())
        assertNull(decoded.getValue(a).state.hue)
    }

    @Test fun nothingSavedDecodesToAnEmptyMap() {
        assertEquals(emptyMap<String, WantedRecord>(), WantedStateCodec.decode(null))
    }

    @Test fun handWrittenValidDocumentDecodes() {
        assertEquals(1L, WantedStateCodec.decode(doc(light())).getValue(a).revision)
    }

    @Test fun incompleteButLegalSeededStateDecodes() {
        val text = doc(light(over = mapOf("mode" to "3", "scene" to "6")))
        val state = WantedStateCodec.decode(text).getValue(a).state
        assertEquals(LightMode.Scene, state.mode)
        assertEquals(6, state.scene)
        assertNull(state.effectTime)
    }

    @Test fun corruptJsonIsRejected() = assertCorrupt("{not json")

    @Test fun wrongVersionIsRejected() = assertCorrupt(doc(light(), version = 2))

    @Test fun extraDocumentFieldIsRejected() = assertCorrupt("{\"version\":1,\"lights\":[],\"more\":1}")

    @Test fun extraRecordFieldIsRejected() = assertCorrupt(doc(light(extra = ",\"x\":1")))

    @Test fun missingRecordFieldIsRejected() = assertCorrupt(doc(light(drop = "hue")))

    @Test fun revisionZeroIsRejected() = assertCorrupt(doc(light(over = mapOf("revision" to "0"))))

    @Test fun lowerCaseAddressIsRejected() = assertCorrupt(doc(light(address = a.lowercase())))

    @Test fun duplicateAddressIsRejected() = assertCorrupt(doc(light(), light()))

    @Test fun outOfRangeHueIsRejected() = assertCorrupt(doc(light(over = mapOf("hue" to "73"))))

    @Test fun legacy360DegreeHueNormalizesToZero() {
        assertEquals(0, WantedStateCodec.decode(doc(light(over = mapOf("hue" to "72")))).getValue(a).state.hue)
    }

    @Test fun outOfRangeBrightnessIsRejected() = assertCorrupt(doc(light(over = mapOf("brightness" to "101"))))

    @Test fun unknownModeIsRejected() = assertCorrupt(doc(light(over = mapOf("mode" to "4"))))
}
