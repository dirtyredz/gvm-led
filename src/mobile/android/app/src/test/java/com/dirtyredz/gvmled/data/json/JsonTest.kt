package com.dirtyredz.gvmled.data.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    private fun assertRejected(text: String) {
        try {
            Json.parse(text)
            fail("Expected JsonException for: $text")
        } catch (_: JsonException) {
        }
    }

    @Test
    fun `parses objects arrays and scalars`() {
        val value = Json.parseObject("""{"a":[1,true,null,"x"],"b":{"c":false},"d":"s"}""")
        val array = value.array("a")!!
        assertEquals(JsonNumber("1"), array.items[0])
        assertEquals(JsonBool(true), array.items[1])
        assertEquals(JsonNull, array.items[2])
        assertEquals(JsonString("x"), array.items[3])
        assertEquals(false, value.obj("b")!!.bool("c"))
        assertEquals("s", value.string("d"))
    }

    @Test
    fun `decodes string escapes`() {
        val value = Json.parse("\"a\\nb\\\"c\\u00e9\\\\\\/\"") as JsonString
        assertEquals("a\nb\"cé\\/", value.value)
    }

    @Test
    fun `only plain integers convert to long`() {
        assertEquals(42L, (Json.parse("42") as JsonNumber).longOrNull())
        assertEquals(-7L, (Json.parse("-7") as JsonNumber).longOrNull())
        assertNull((Json.parse("42.5") as JsonNumber).longOrNull())
        assertNull((Json.parse("1e3") as JsonNumber).longOrNull())
        assertNull(JsonNumber("99999999999999999999").longOrNull())
    }

    @Test
    fun `rejects strictness violations`() {
        assertRejected("""{"a":1,"a":2}""")
        assertRejected("""{"a":1} x""")
        assertRejected("{}{}")
        assertRejected("[1,2,]")
        assertRejected("""{"a":1,}""")
        assertRejected("{a:1}")
        assertRejected("\"tab\tinside\"")
        assertRejected("\"line\nbreak\"")
        assertRejected("""{"a":""")
        assertRejected("[1,")
        assertRejected("\"unterminated")
        assertRejected("")
        assertRejected("01")
    }

    @Test
    fun `limits nesting depth to 32`() {
        fun nested(levels: Int) = "[".repeat(levels) + "]".repeat(levels)
        Json.parse(nested(32))
        assertRejected(nested(34))
    }

    @Test
    fun `parseObject rejects non objects`() {
        try {
            Json.parseObject("[1]")
            fail("Expected JsonException")
        } catch (_: JsonException) {
        }
    }

    @Test
    fun `write then parse round trips`() {
        val tricky = JsonString("quote\" back\\ line\n tab\t ctrl\u0001 é")
        val value = JsonObject(
            linkedMapOf(
                "s" to tricky,
                "n" to JsonNumber("-12"),
                "list" to JsonArray(listOf(JsonBool(false), JsonNull, JsonObject(emptyMap()))),
            ),
        )
        val text = Json.write(value)
        assertEquals(value, Json.parse(text))
        assertTrue(text.none { it < ' ' })
    }
}
