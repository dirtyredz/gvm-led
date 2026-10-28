package com.dirtyredz.gvmled.data.local

import com.dirtyredz.gvmled.data.model.BluetoothAddress
import com.dirtyredz.gvmled.data.json.Json
import com.dirtyredz.gvmled.data.json.JsonArray
import com.dirtyredz.gvmled.data.json.JsonException
import com.dirtyredz.gvmled.data.json.JsonNumber
import com.dirtyredz.gvmled.data.json.JsonObject
import com.dirtyredz.gvmled.data.json.JsonString
import com.dirtyredz.gvmled.data.model.KnownLight
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.model.LightGroup

/** What SharedPreferences holds, read without interpretation (version 1 keys included). */
data class StoredCatalog(
    val catalogJson: String?,
    val legacyVersion: Int,
    val legacyAddresses: String?,
    val legacyNames: Map<String, String>,
)

/** How a stored catalog resolved: usable (and whether it must be written back), or unreadable. */
sealed interface CatalogResolution {
    data class Ready(val catalog: LightCatalog, val needsWrite: Boolean) : CatalogResolution
    data class Unreadable(val reason: String) : CatalogResolution
}

/**
 * Pure catalog persistence rules. Version 2 is one JSON document of lights and groups. Version 1
 * (addresses plus `name.<address>` keys) migrates once, keeping custom names and giving the others
 * their old default "Light N". Only an uninitialized installation is seeded with the owner's two
 * lights. Corrupt or newer data is reported, never silently replaced with seeds.
 */
object LightCatalogCodec {
    const val VERSION = 2

    /** The owner's lights (protocol.md, PC scan results): light 1, then light 2. */
    val SEEDED_ADDRESSES = listOf("94:C9:60:E5:D7:6F", "94:C9:60:EB:67:E6")

    fun seeded() = LightCatalog(SEEDED_ADDRESSES.mapIndexed { i, address -> KnownLight(address, "Light ${i + 1}") }, emptyList())

    fun resolve(stored: StoredCatalog): CatalogResolution = when {
        stored.catalogJson != null -> try {
            CatalogResolution.Ready(decode(stored.catalogJson), needsWrite = false)
        } catch (failure: CatalogFormatException) {
            CatalogResolution.Unreadable(failure.message ?: "Saved lights are unreadable")
        }
        stored.legacyVersion == 1 -> fromVersion1(stored)
        stored.legacyVersion == 0 -> CatalogResolution.Ready(seeded(), needsWrite = true)
        else -> CatalogResolution.Unreadable("Saved lights use unsupported version ${stored.legacyVersion}")
    }

    private fun fromVersion1(stored: StoredCatalog): CatalogResolution {
        val addresses = stored.legacyAddresses?.split(",")?.filter { it.isNotBlank() }?.map(BluetoothAddress::normalize)
            ?: SEEDED_ADDRESSES
        val lights = addresses.distinct().mapIndexed { index, address ->
            val custom = stored.legacyNames[address]?.trim()?.take(LightCatalog.MAX_NAME_LENGTH)?.trim()
            KnownLight(address, custom?.takeIf { it.isNotEmpty() } ?: "Light ${index + 1}")
        }
        val catalog = LightCatalog(lights, emptyList())
        return catalog.problem()?.let { CatalogResolution.Unreadable("Version 1 lights are invalid: $it") }
            ?: CatalogResolution.Ready(catalog, needsWrite = true)
    }

    fun encode(catalog: LightCatalog): String = Json.write(
        JsonObject(
            linkedMapOf(
                "version" to JsonNumber(VERSION.toString()),
                "lights" to JsonArray(catalog.lights.map {
                    JsonObject(linkedMapOf("address" to JsonString(it.address), "name" to JsonString(it.name)))
                }),
                "groups" to JsonArray(catalog.groups.map { group ->
                    JsonObject(
                        linkedMapOf(
                            "id" to JsonString(group.id),
                            "name" to JsonString(group.name),
                            "members" to JsonArray(group.members.map(::JsonString)),
                        ),
                    )
                }),
            ),
        ),
    )

    /** Throws [CatalogFormatException] for anything but a valid version-2 catalog. */
    fun decode(text: String): LightCatalog {
        val root = try {
            Json.parseObject(text)
        } catch (failure: JsonException) {
            throw CatalogFormatException("Saved lights are corrupt (${failure.message})")
        }
        val version = root.long("version")
        if (version != VERSION.toLong()) throw CatalogFormatException("Saved lights use unsupported version $version")
        if (root.keys != setOf("version", "lights", "groups")) throw CatalogFormatException("Saved lights have unexpected fields")
        val lights = root.array("lights")?.items?.map { item ->
            val light = item as? JsonObject ?: corrupt()
            if (light.keys != setOf("address", "name")) corrupt()
            KnownLight(light.string("address") ?: corrupt(), light.string("name") ?: corrupt())
        } ?: corrupt()
        val groups = root.array("groups")?.items?.map { item ->
            val group = item as? JsonObject ?: corrupt()
            if (group.keys != setOf("id", "name", "members")) corrupt()
            LightGroup(
                id = group.string("id") ?: corrupt(),
                name = group.string("name") ?: corrupt(),
                members = group.array("members")?.items?.map { (it as? JsonString)?.value ?: corrupt() } ?: corrupt(),
            )
        } ?: corrupt()
        val catalog = LightCatalog(lights, groups)
        catalog.problem()?.let { throw CatalogFormatException("Saved lights are invalid: $it") }
        return catalog
    }

    private fun corrupt(): Nothing = throw CatalogFormatException("Saved lights are corrupt")
}

class CatalogFormatException(message: String) : Exception(message)
