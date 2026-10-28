package com.dirtyredz.gvmled

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dirtyredz.gvmled.feature.lights.LightsScreen
import com.dirtyredz.gvmled.feature.lights.LightsViewModel
import com.dirtyredz.gvmled.feature.lights.RadioActions
import com.dirtyredz.gvmled.ui.theme.GvmLedTheme

/**
 * Android entry: composition, lifecycle and the system dialogs only an activity can launch.
 * Connections run while the activity is started (foreground only); a configuration change keeps them.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as GvmApplication).container

    private val viewModel: LightsViewModel by viewModels {
        viewModelFactory { initializer { LightsViewModel(container.lights, container.radio.state) } }
    }

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            container.radio.permissionsRequested()
        }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { container.radio.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = RadioActions(
            requestPermissions = { permissionRequest.launch(container.radio.requiredPermissions.toTypedArray()) },
            openAppSettings = ::openAppSettings,
            enableBluetooth = ::requestBluetoothOn,
        )
        setContent {
            GvmLedTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LightsScreen(state, actions, viewModel::rename)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.radio.refresh()
        container.coordinator.start()
    }

    override fun onStop() {
        if (!isChangingConfigurations) container.coordinator.stop()
        super.onStop()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    // Only offered in RadioState.Off, which is reached after the Bluetooth permissions are granted.
    @SuppressLint("MissingPermission")
    private fun requestBluetoothOn() {
        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
}
