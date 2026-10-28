package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.connection.LightConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** User-chosen display names by normalized address; implemented by data/local/LightPreferences. */
interface LightNameStore {
    val names: StateFlow<Map<String, String>>

    /** `null` restores the default name. */
    fun rename(address: String, name: String?)
}

/** One configured light: its observed connection and the name to show. */
data class LightEntry(val connection: LightConnection, val name: String, val isCustomName: Boolean)

/**
 * Per-light observations joined with names. Read-only in this slice; wanted state and actions are
 * added with control planning.
 */
class LightsRepository(
    connections: StateFlow<List<LightConnection>>,
    val otherLights: StateFlow<Set<String>>,
    private val names: LightNameStore,
) {
    val lights: Flow<List<LightEntry>> = combine(connections, names.names) { list, custom ->
        list.mapIndexed { index, connection ->
            val chosen = custom[connection.address]
            LightEntry(connection, chosen ?: "Light ${index + 1}", chosen != null)
        }
    }

    /** Trims and caps the name; a blank name restores the default. */
    fun rename(address: String, name: String) {
        val cleaned = name.trim().take(MAX_NAME_LENGTH)
        names.rename(address, cleaned.ifEmpty { null })
    }

    companion object {
        const val MAX_NAME_LENGTH = 24
    }
}
