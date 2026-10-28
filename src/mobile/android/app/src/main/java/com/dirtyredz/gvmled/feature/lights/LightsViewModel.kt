package com.dirtyredz.gvmled.feature.lights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtyredz.gvmled.connection.RadioState
import com.dirtyredz.gvmled.data.repository.LightsRepository
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Read-only live state per light plus renaming. Bluetooth permission/enable flows stay in the activity. */
class LightsViewModel(
    private val repository: LightsRepository,
    radio: StateFlow<RadioState>,
    zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    val state: StateFlow<LightsUiState> =
        combine(radio, repository.lights, repository.otherLights) { radioState, lights, others ->
            LightsUiState(
                radio = radioState,
                lights = lights.map { it.toCardModel(zone()) },
                otherLights = others.map(::addressSuffix).sorted(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LightsUiState(radio.value))

    fun rename(address: String, name: String) = repository.rename(address, name)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
