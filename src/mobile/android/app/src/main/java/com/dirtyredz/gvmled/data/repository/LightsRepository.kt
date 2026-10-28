package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.connection.LightConnection
import com.dirtyredz.gvmled.data.model.LightCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** One known light: its observed connection and its stored name. */
data class LightEntry(val connection: LightConnection, val name: String)

/**
 * Per-light observations joined with catalog names, in catalog order. A light the coordinator has
 * not reconciled yet shows as not connected. Read-only: catalog changes go through
 * [LightCatalogRepository]; wanted state and actions arrive with control planning.
 */
class LightsRepository(
    connections: StateFlow<List<LightConnection>>,
    catalog: StateFlow<LightCatalog>,
) {
    val lights: Flow<List<LightEntry>> = combine(connections, catalog) { list, known ->
        val byAddress = list.associateBy { it.address }
        known.lights.map { light -> LightEntry(byAddress[light.address] ?: LightConnection(light.address), light.name) }
    }
}
