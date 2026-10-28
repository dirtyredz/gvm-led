package com.dirtyredz.gvmled.feature.lights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtyredz.gvmled.data.model.ControlTarget
import com.dirtyredz.gvmled.data.model.LightCatalog
import com.dirtyredz.gvmled.data.repository.ControlResult
import com.dirtyredz.gvmled.data.repository.LightControlRepository
import com.dirtyredz.gvmled.data.repository.LightsRepository
import com.dirtyredz.gvmled.light.connection.RadioState
import com.dirtyredz.gvmled.light.control.LightEdit
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Live state and controls for the selected individual light or named group. Every action is applied
 * to each member's own wanted state through [controls]; this never writes to a light directly.
 * Bluetooth permission and enable flows stay in the activity; naming and groups are in Settings.
 */
class LightsViewModel(
    repository: LightsRepository,
    catalog: StateFlow<LightCatalog>,
    radio: StateFlow<RadioState>,
    private val controls: LightControlRepository,
    zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val selected = MutableStateFlow<ControlTarget?>(null)
    private val message = MutableStateFlow<String?>(null)

    private val inputs = combine(selected, message, controls.problem) { target, note, problem -> Triple(target, note, problem) }

    val state: StateFlow<LightsUiState> =
        combine(radio, repository.lights, catalog, inputs) { radioState, lights, known, (target, note, problem) ->
            lightsState(radioState, lights, known, target, zone(), note, problem)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            LightsUiState(radio.value, isEmpty = catalog.value.lights.isEmpty()),
        )

    fun select(target: ControlTarget) {
        selected.value = target
        message.value = null
    }

    /** Applies [edit] to the target currently shown (its member snapshot is taken now). */
    fun apply(edit: LightEdit) {
        val target = state.value.target ?: return
        viewModelScope.launch {
            message.value = when (val result = controls.apply(target, edit)) {
                is ControlResult.Accepted -> null
                is ControlResult.Rejected -> result.reason
            }
        }
    }

    fun resetSettings() {
        viewModelScope.launch { controls.resetUnreadable() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
