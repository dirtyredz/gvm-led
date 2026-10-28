package com.dirtyredz.gvmled.data.repository

import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.light.connection.LightConnection
import com.dirtyredz.gvmled.light.control.ApplyStatus
import com.dirtyredz.gvmled.light.control.WantedRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** One known light: its stored name, observed connection, wanted intent and apply status. */
data class LightEntry(
    val connection: LightConnection,
    val name: String,
    val wanted: WantedRecord? = null,
    val apply: ApplyStatus = ApplyStatus.NoSettings,
)

/**
 * Per-light observations, intent and apply status joined with catalog names, in catalog order. A
 * light the coordinator has not reconciled yet shows as not connected. Read-only: catalog changes go
 * through [LightCatalogRepository] and intent changes through [LightControlRepository].
 */
class LightsRepository(
    connections: StateFlow<List<LightConnection>>,
    catalog: StateFlow<LightCatalog>,
    wanted: StateFlow<Map<String, WantedRecord>>,
    statuses: StateFlow<Map<String, ApplyStatus>>,
) {
    val lights: Flow<List<LightEntry>> = combine(connections, catalog, wanted, statuses) { list, known, intents, applied ->
        val byAddress = list.associateBy { it.address }
        known.lights.map { light ->
            LightEntry(
                connection = byAddress[light.address] ?: LightConnection(light.address),
                name = light.name,
                wanted = intents[light.address],
                apply = applied[light.address] ?: ApplyStatus.NoSettings,
            )
        }
    }
}
