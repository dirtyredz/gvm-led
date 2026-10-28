package com.dirtyredz.gvmled.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtyredz.gvmled.update.UpdateController
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** App identity and the update card. All network, file and installer work stays in [UpdateController]. */
class SettingsViewModel(
    private val updates: UpdateController,
    private val sdkInt: Int,
    private val developmentPackage: String?,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = updates.state
        .map { it.toSettingsUiState(sdkInt, zone(), developmentPackage) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            updates.state.value.toSettingsUiState(sdkInt, zone(), developmentPackage),
        )

    fun perform(action: UpdateAction) = when (action) {
        UpdateAction.Check -> updates.check()
        UpdateAction.DownloadAndInstall, UpdateAction.RetryDownload -> updates.download()
        UpdateAction.Install, UpdateAction.InstallAgain, UpdateAction.AllowInstalls -> updates.install()
        UpdateAction.OpenReleasePage -> updates.openReleasePage()
    }
}
