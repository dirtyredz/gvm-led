package com.dirtyredz.gvmled.feature.lights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.repository.LightsRepository
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Read-only live state for the selected individual light or named group. Bluetooth permission and
 * enable flows stay in the activity; naming and groups are managed from Settings.
 */
class LightsViewModel(
    repository: LightsRepository,
    catalog: StateFlow<LightCatalog>,
    radio: StateFlow<RadioState>,
    zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val selected = MutableStateFlow<String?>(null)

    val state: StateFlow<LightsUiState> =
        combine(radio, repository.lights, catalog, selected) { radioState, lights, known, key ->
            lightsState(radioState, lights, known, key, zone())
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            LightsUiState(radio.value, isEmpty = catalog.value.lights.isEmpty()),
        )

    fun select(key: String) {
        selected.value = key
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
