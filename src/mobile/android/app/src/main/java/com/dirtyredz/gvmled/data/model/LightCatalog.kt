package com.dirtyredz.gvmled.data.model

import java.util.Locale

/** Every known light and every named group, persisted together so membership always refers to saved lights. */
data class LightCatalog(val lights: List<KnownLight>, val groups: List<LightGroup>) {
    val addresses: List<String> get() = lights.map { it.address }

    fun light(address: String): KnownLight? = lights.firstOrNull { it.address == address }

    fun group(id: String): LightGroup? = groups.firstOrNull { it.id == id }

    /** The first rule this catalog breaks, or null when it is valid. */
    fun problem(): String? {
        val addresses = lights.map { it.address }
        return when {
            addresses.any { !ADDRESS.matches(it) } -> "A light address is not a normalized Bluetooth address"
            addresses.toSet().size != addresses.size -> "A light is listed twice"
            lights.any { !isValidName(it.name) } -> "A light name is blank or too long"
            groups.any { it.id.isBlank() } || groups.map { it.id }.toSet().size != groups.size -> "Group IDs are not unique"
            groups.any { !isValidName(it.name) } -> "A group name is blank or too long"
            groups.map { it.name.lowercase(Locale.ROOT) }.toSet().size != groups.size -> "Two groups have the same name"
            groups.any { it.members.isEmpty() } -> "A group has no lights"
            groups.any { it.members.toSet().size != it.members.size } -> "A group lists a light twice"
            groups.any { group -> group.members.any { it !in addresses } } -> "A group refers to a light that is not saved"
            else -> null
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 24
        val EMPTY = LightCatalog(emptyList(), emptyList())
        private val ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

        fun isValidName(name: String) = name.isNotBlank() && name == name.trim() && name.length <= MAX_NAME_LENGTH
    }
}
