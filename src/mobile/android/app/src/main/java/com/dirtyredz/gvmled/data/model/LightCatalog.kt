package com.dirtyredz.gvmled.data.model

import java.util.Locale

/** Every known light and every named group, persisted together so membership always refers to saved lights. */
data class LightCatalog(val lights: List<KnownLight>, val groups: List<LightGroup>) {
    val addresses: List<String> get() = lights.map { it.address }

    fun light(address: String): KnownLight? = lights.firstOrNull { it.address == address }

    fun group(id: String): LightGroup? = groups.firstOrNull { it.id == id }

    /**
     * The first rule this catalog breaks, as an owner-facing sentence, or null when it is valid. This
     * is the one catalog validation: persistence decoding and every repository mutation use it.
     */
    fun problem(): String? {
        val addresses = lights.map { it.address }
        val duplicateGroup = groups.groupBy { it.name.lowercase(Locale.ROOT) }.values.firstOrNull { it.size > 1 }
        return when {
            addresses.any { !ADDRESS.matches(it) } -> "A light address is not a normalized Bluetooth address"
            addresses.toSet().size != addresses.size -> "That light is already added"
            lights.any { !isValidName(it.name) } -> NAME_RULE
            groups.any { it.id.isBlank() } || groups.map { it.id }.toSet().size != groups.size -> "Group IDs are not unique"
            groups.any { !isValidName(it.name) } -> NAME_RULE
            duplicateGroup != null -> "Another group is already called \"${duplicateGroup.last().name}\""
            groups.any { it.members.isEmpty() } -> "Choose at least one light"
            groups.any { it.members.toSet().size != it.members.size } -> "A group lists a light twice"
            groups.any { group -> group.members.any { it !in addresses } } -> "A chosen light is no longer saved"
            else -> null
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 24
        const val NAME_RULE = "Use a name of 1 to $MAX_NAME_LENGTH characters"
        val EMPTY = LightCatalog(emptyList(), emptyList())
        private val ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

        fun isValidName(name: String) = name.isNotBlank() && name == name.trim() && name.length <= MAX_NAME_LENGTH
    }
}
