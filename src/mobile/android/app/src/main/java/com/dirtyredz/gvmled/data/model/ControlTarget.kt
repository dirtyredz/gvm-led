package com.dirtyredz.gvmled.data.model

/**
 * What the owner has selected to view or control: one light by address, or a named group by its
 * stable ID. A group is only a selection; resolving it gives a snapshot of member addresses, and
 * every action is applied to each member's own wanted state.
 */
sealed interface ControlTarget {
    /** The saved-selection encoding. Renaming a light or group never changes it. */
    val key: String

    /** The member addresses in catalog order, deduplicated; empty when the target no longer exists. */
    fun members(catalog: LightCatalog): List<String>

    /** True while the light or group is still in [catalog]. */
    fun exists(catalog: LightCatalog): Boolean

    data class Light(val address: String) : ControlTarget {
        override val key: String get() = LIGHT_PREFIX + address
        override fun members(catalog: LightCatalog) = listOfNotNull(catalog.light(address)?.address)
        override fun exists(catalog: LightCatalog) = catalog.light(address) != null
    }

    data class Group(val id: String) : ControlTarget {
        override val key: String get() = GROUP_PREFIX + id
        override fun members(catalog: LightCatalog): List<String> {
            val chosen = catalog.group(id)?.members?.toSet() ?: return emptyList()
            return catalog.addresses.filter { it in chosen }
        }
        override fun exists(catalog: LightCatalog) = catalog.group(id) != null
    }

    companion object {
        private const val LIGHT_PREFIX = "light:"
        private const val GROUP_PREFIX = "group:"

        /** Decodes a saved selection; null for anything that is not a target key. */
        fun parse(key: String?): ControlTarget? = when {
            key == null -> null
            key.startsWith(LIGHT_PREFIX) -> Light(key.removePrefix(LIGHT_PREFIX))
            key.startsWith(GROUP_PREFIX) -> Group(key.removePrefix(GROUP_PREFIX))
            else -> null
        }

        /** [selected] while it still exists, otherwise the first light, or null when there are no lights. */
        fun resolve(selected: ControlTarget?, catalog: LightCatalog): ControlTarget? =
            selected?.takeIf { it.exists(catalog) } ?: catalog.lights.firstOrNull()?.let { Light(it.address) }
    }
}
