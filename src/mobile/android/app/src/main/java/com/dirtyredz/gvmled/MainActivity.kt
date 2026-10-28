package com.dirtyredz.gvmled

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dirtyredz.gvmled.ui.components.RadioActions
import com.dirtyredz.gvmled.ui.navigation.GvmApp
import com.dirtyredz.gvmled.ui.theme.GvmLedTheme
import com.dirtyredz.gvmled.update.UpdateEffect
import kotlinx.coroutines.launch

/**
 * Android entry: lifecycle, the composition root and the system screens only an activity can
 * launch (permission and Bluetooth dialogs, install-source settings, the package installer, the
 * browser). Connections run while the activity is started (foreground only); a configuration change
 * keeps them. Leaving the foreground also stops unfinished update network work.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as GvmApplication).container

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            container.radio.permissionsRequested()
        }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { container.radio.refresh() }

    private val installPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            container.updates.installPermissionReturned()
        }

    // The installer's result code is not trusted; the controller reads the installed version instead.
    private val installer =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { container.updates.installerReturned() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = RadioActions(
            requestPermissions = { permissionRequest.launch(container.radio.requiredPermissions.toTypedArray()) },
            openAppSettings = ::openAppSettings,
            enableBluetooth = ::requestBluetoothOn,
        )
        setContent { GvmLedTheme { GvmApp(container, actions) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { container.updates.effects.collect(::launchUpdateEffect) }
        }
    }

    override fun onStart() {
        super.onStart()
        container.radio.refresh()
        container.coordinator.start()
        container.updates.refreshInstalled()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            container.coordinator.stop()
            container.updates.onBackground()
        }
        super.onStop()
    }

    private fun launchUpdateEffect(effect: UpdateEffect) {
        val launcher = container.installLauncher
        try {
            when (effect) {
                is UpdateEffect.LaunchInstaller -> installer.launch(launcher.installerIntent(effect.apk))
                UpdateEffect.OpenInstallPermission -> installPermission.launch(launcher.installPermissionIntent())
                is UpdateEffect.OpenReleasePage -> startActivity(launcher.releasePageIntent(effect.url))
            }
        } catch (_: ActivityNotFoundException) {
            // No installer/settings/browser to hand off to; the Settings screen stays usable.
        }
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
