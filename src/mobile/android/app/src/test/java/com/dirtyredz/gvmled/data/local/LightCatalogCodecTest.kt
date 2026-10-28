package com.dirtyredz.gvmled.data.local

import com.dirtyredz.gvmled.data.local.CatalogResolution.Ready
import com.dirtyredz.gvmled.data.local.CatalogResolution.Unreadable
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LightCatalogCodecTest {
    private val a = "94:C9:60:E5:D7:6F"
    private val b = "94:C9:60:EB:67:E6"
    private val c = "94:C9:60:AA:BB:CC"

    private fun stored(json: String? = null, version: Int = 0, addresses: String? = null, names: Map<String, String> = emptyMap()) =
        StoredCatalog(json, version, addresses, names)

    private fun json(lights: String, groups: String = "", extra: String = "", version: Int = 2) =
        """{"version":$version,"lights":[$lights],"groups":[$groups]$extra}"""

    private fun light(address: String, name: String) = """{"address":"$address","name":"$name"}"""

    private fun assertDecodeFails(text: String) {
        try {
            LightCatalogCodec.decode(text)
            fail("expected CatalogFormatException for $text")
        } catch (expected: CatalogFormatException) {
            // expected
        }
    }

    @Test fun freshInstallSeedsTheTwoLightsAndRequestsAWrite() {
        val result = LightCatalogCodec.resolve(stored()) as Ready
        assertEquals(listOf(KnownLight(a, "Light 1"), KnownLight(b, "Light 2")), result.catalog.lights)
        assertTrue(result.catalog.groups.isEmpty())
        assertTrue(result.needsWrite)
    }

    @Test fun versionOneKeepsCustomNamesAndNumbersTheRest() {
        val result = LightCatalogCodec.resolve(stored(version = 1, addresses = "$a,$b,$c", names = mapOf(b to "  Fill  "))) as Ready
        assertEquals(listOf(KnownLight(a, "Light 1"), KnownLight(b, "Fill"), KnownLight(c, "Light 3")), result.catalog.lights)
        assertTrue(result.needsWrite)
    }

    @Test fun versionOneNormalizesAndDeduplicatesAddressesWithoutReseeding() {
        val result = LightCatalogCodec.resolve(stored(version = 1, addresses = "${c.lowercase()},$c,,${a.lowercase()}")) as Ready
        assertEquals(listOf(KnownLight(c, "Light 1"), KnownLight(a, "Light 2")), result.catalog.lights)
        assertFalse(result.catalog.lights.any { it.address == b })
    }

    @Test fun versionOneWithoutAddressesFallsBackToSeededAddresses() {
        val result = LightCatalogCodec.resolve(stored(version = 1)) as Ready
        assertEquals(LightCatalogCodec.SEEDED_ADDRESSES, result.catalog.addresses)
        assertTrue(result.needsWrite)
    }

    @Test fun savedVersionTwoCatalogWithGroupsRoundTrips() {
        val catalog = LightCatalog(
            listOf(KnownLight(a, "Key"), KnownLight(b, "Fill"), KnownLight(c, "Back")),
            listOf(LightGroup("g1", "Key and Fill", listOf(a, b)), LightGroup("g2", "Fill and Back", listOf(b, c))),
        )
        val result = LightCatalogCodec.resolve(stored(LightCatalogCodec.encode(catalog), 2)) as Ready
        assertEquals(catalog, result.catalog)
        assertFalse(result.needsWrite)
    }

    @Test fun emptyCatalogRoundTrips() {
        assertEquals(LightCatalog.EMPTY, LightCatalogCodec.decode(LightCatalogCodec.encode(LightCatalog.EMPTY)))
    }

    @Test fun corruptJsonIsUnreadableAndNeverSeeded() {
        assertTrue(LightCatalogCodec.resolve(stored("not json")) is Unreadable)
        assertTrue(LightCatalogCodec.resolve(stored("not json", version = 1)) is Unreadable)
    }

    @Test fun newerVersionIsUnreadable() {
        assertTrue(LightCatalogCodec.resolve(stored(json(light(a, "Light 1"), version = 3))) is Unreadable)
    }

    @Test fun legacyVersionTwoWithoutCatalogJsonIsUnreadable() {
        assertTrue(LightCatalogCodec.resolve(stored(version = 2)) is Unreadable)
    }

    @Test fun decodeRejectsDuplicateAddresses() = assertDecodeFails(json("${light(a, "One")},${light(a, "Two")}"))

    @Test fun decodeRejectsAGroupWithAnUnknownMember() =
        assertDecodeFails(json(light(a, "One"), """{"id":"g1","name":"G","members":["$b"]}"""))

    @Test fun decodeRejectsAnEmptyGroup() = assertDecodeFails(json(light(a, "One"), """{"id":"g1","name":"G","members":[]}"""))

    @Test fun decodeRejectsGroupNamesThatDifferOnlyByCase() = assertDecodeFails(
        json(light(a, "One"), """{"id":"g1","name":"Key","members":["$a"]},{"id":"g2","name":"KEY","members":["$a"]}"""),
    )

    @Test fun decodeRejectsExtraFields() {
        assertDecodeFails(json(light(a, "One"), extra = ""","extra":1"""))
        assertDecodeFails(json("""{"address":"$a","name":"One","colour":"red"}"""))
    }
}
