package com.dirtyredz.gvmled.data.local

import com.dirtyredz.gvmled.data.json.Json
import com.dirtyredz.gvmled.data.json.JsonArray
import com.dirtyredz.gvmled.data.json.JsonBool
import com.dirtyredz.gvmled.data.json.JsonException
import com.dirtyredz.gvmled.data.json.JsonNull
import com.dirtyredz.gvmled.data.json.JsonNumber
import com.dirtyredz.gvmled.data.json.JsonObject
import com.dirtyredz.gvmled.data.json.JsonString
import com.dirtyredz.gvmled.data.json.JsonValue
import com.dirtyredz.gvmled.data.model.BluetoothAddress
import com.dirtyredz.gvmled.light.control.LightMode
import com.dirtyredz.gvmled.light.control.Field
import com.dirtyredz.gvmled.light.control.WantedRecord
import com.dirtyredz.gvmled.light.control.WantedState

/**
 * Pure rules for the saved wanted state (version 1): one JSON document of per-light records keyed by
 * normalized address, each with its revision. Unknown values are saved as JSON null, never as a
 * default. Anything but a valid version-1 document is reported as unreadable, never replaced.
 */
object WantedStateCodec {
    const val VERSION = 1
    private val RECORD_KEYS = setOf(
        "address", "revision", "power", "mode", "brightness", "cct", "hue", "saturation", "scene", "effectTime",
    )

    fun encode(records: Map<String, WantedRecord>): String = Json.write(
        JsonObject(
            linkedMapOf(
                "version" to JsonNumber(VERSION.toString()),
                "lights" to JsonArray(records.map { (address, record) -> encodeRecord(address, record) }),
            ),
        ),
    )

    private fun encodeRecord(address: String, record: WantedRecord): JsonObject {
        val state = record.state
        return JsonObject(
            linkedMapOf(
                "address" to JsonString(address),
                "revision" to JsonNumber(record.revision.toString()),
                "power" to JsonBool(state.power),
                "mode" to number(state.mode.wire),
                "brightness" to number(state.brightness),
                "cct" to number(state.cct),
                "hue" to number(state.hue),
                "saturation" to number(state.saturation),
                "scene" to number(state.scene),
                "effectTime" to number(state.effectTime),
            ),
        )
    }

    private fun number(value: Int?): JsonValue = value?.let { JsonNumber(it.toString()) } ?: JsonNull

    /** Null text (nothing saved yet) is an empty map. Throws [WantedStateFormatException] otherwise. */
    fun decode(text: String?): Map<String, WantedRecord> {
        if (text == null) return emptyMap()
        val root = try {
            Json.parseObject(text)
        } catch (failure: JsonException) {
            throw WantedStateFormatException("Saved light settings are corrupt (${failure.message})")
        }
        val version = root.long("version")
        if (version != VERSION.toLong()) throw WantedStateFormatException("Saved light settings use unsupported version $version")
        if (root.keys != setOf("version", "lights")) corrupt()
        val records = LinkedHashMap<String, WantedRecord>()
        for (item in root.array("lights")?.items ?: corrupt()) {
            val light = item as? JsonObject ?: corrupt()
            if (light.keys != RECORD_KEYS) corrupt()
            val address = light.string("address") ?: corrupt()
            if (!BluetoothAddress.isNormalized(address) || address in records) corrupt()
            records[address] = decodeRecord(light)
        }
        return records
    }

    private fun decodeRecord(light: JsonObject): WantedRecord {
        val revision = light.long("revision")?.takeIf { it > 0 } ?: corrupt()
        val state = WantedState(
            power = light.bool("power") ?: corrupt(),
            mode = LightMode.fromWire(required(light, "mode")) ?: corrupt(),
            brightness = required(light, "brightness"),
            cct = optional(light, "cct"),
            hue = optional(light, "hue")?.let(Field.Hue::normalize),
            saturation = optional(light, "saturation"),
            scene = optional(light, "scene"),
            effectTime = optional(light, "effectTime"),
        )
        // Seeded intent may lack values (unknown stays unknown), but every present value must be legal.
        if (state.brightness !in 0..100 || Field.entries.any { field -> field.read(state)?.let { !field.isLegal(it) } == true }) {
            corrupt()
        }
        return WantedRecord(revision, state)
    }

    private fun required(light: JsonObject, key: String): Int = optional(light, key) ?: corrupt()

    private fun optional(light: JsonObject, key: String): Int? = when (val value = light[key]) {
        JsonNull -> null
        is JsonNumber -> value.longOrNull()?.takeIf { it in 0..255 }?.toInt() ?: corrupt()
        else -> corrupt()
    }

    private fun corrupt(): Nothing = throw WantedStateFormatException("Saved light settings are corrupt")
}

class WantedStateFormatException(message: String) : Exception(message)
