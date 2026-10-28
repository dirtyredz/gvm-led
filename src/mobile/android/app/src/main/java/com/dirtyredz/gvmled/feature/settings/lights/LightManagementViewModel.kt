package com.dirtyredz.gvmled.feature.settings.lights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtyredz.gvmled.light.connection.Candidate
import com.dirtyredz.gvmled.light.connection.LightDiscovery
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.repository.CatalogResult
import com.dirtyredz.gvmled.data.repository.LightCatalogRepository
import com.dirtyredz.gvmled.data.repository.LightEntry
import com.dirtyredz.gvmled.data.repository.LightsRepository
import com.dirtyredz.gvmled.feature.lights.addressSuffix
import com.dirtyredz.gvmled.feature.lights.statusText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ManagedLight(val address: String, val addressSuffix: String, val name: String, val status: String)

data class CandidateRow(val address: String, val seen: String)

data class LightManagementUiState(
    val radio: RadioState = RadioState.Ready,
    val lights: List<ManagedLight> = emptyList(),
    val candidates: List<CandidateRow> = emptyList(),
    val finding: Boolean = false,
    /** Why saved lights cannot be used (changes paused); offers a deliberate reset. */
    val problem: String? = null,
    /** Outcome of the last add/rename, e.g. a rejected name. */
    val message: String? = null,
    val suggestedName: String = "Light 1",
)

/**
 * Settings > Lights: rename known lights and enroll discovered `BT_LED` devices by address. Adding is
 * not a compatibility claim: the coordinator then connects and the status shows whether the legacy
 * GATT profile and a valid hello were established. Enrollment sends no set commands.
 */
class LightManagementViewModel(
    private val catalog: LightCatalogRepository,
    lights: LightsRepository,
    private val discovery: LightDiscovery,
    radio: StateFlow<RadioState>,
    monotonicMillis: () -> Long,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val ticks = flow {
        while (true) {
            emit(monotonicMillis())
            delay(1_000)
        }
    }

    private val base = combine(radio, lights.lights, catalog.catalog, catalog.problem, message) { r, entries, known, problem, note ->
        Inputs(r, entries, known, problem, note)
    }

    val state: StateFlow<LightManagementUiState> =
        combine(base, discovery.candidates, discovery.finding, ticks) { inputs, candidates, finding, now ->
            managementState(inputs, candidates, finding, now)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LightManagementUiState(radio.value))

    fun find() = discovery.find()

    fun add(address: String, name: String) = run { catalog.addLight(address, name) }

    fun rename(address: String, name: String) = run { catalog.rename(address, name) }

    fun resetSaved() = run { catalog.resetUnreadable() }

    fun dismissMessage() {
        message.value = null
    }

    private fun run(action: suspend () -> CatalogResult) {
        viewModelScope.launch {
            message.value = (action() as? CatalogResult.Rejected)?.reason
        }
    }
}

internal data class Inputs(
    val radio: RadioState,
    val entries: List<LightEntry>,
    val catalog: LightCatalog,
    val problem: String?,
    val message: String?,
)

internal fun managementState(inputs: Inputs, candidates: List<Candidate>, finding: Boolean, now: Long) = LightManagementUiState(
    radio = inputs.radio,
    lights = inputs.entries.map {
        ManagedLight(it.connection.address, addressSuffix(it.connection.address), it.name, statusText(it.connection.status, it.connection.lastError))
    },
    candidates = candidates.map { CandidateRow(it.address, seenText(now - it.lastSeenMillis)) },
    finding = finding,
    problem = inputs.problem,
    message = inputs.message,
    suggestedName = suggestedName(inputs.catalog),
)

internal fun seenText(ageMillis: Long): String {
    val seconds = (ageMillis / 1_000).coerceAtLeast(0)
    return if (seconds < 2) "seen just now" else "seen $seconds s ago"
}

/** The lowest "Light N" not already used, so a new light gets a distinct default label. */
internal fun suggestedName(catalog: LightCatalog): String {
    val used = catalog.lights.map { it.name }.toSet()
    return generateSequence(1) { it + 1 }.map { "Light $it" }.first { it !in used }
}
