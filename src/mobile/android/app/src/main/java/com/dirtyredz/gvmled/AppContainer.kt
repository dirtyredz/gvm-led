package com.dirtyredz.gvmled

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.dirtyredz.gvmled.light.ble.BleScanner
import com.dirtyredz.gvmled.light.ble.BluetoothRadio
import com.dirtyredz.gvmled.light.ble.GattConnection
import com.dirtyredz.gvmled.light.connection.AppVisibility
import com.dirtyredz.gvmled.light.connection.ConnectionCoordinator
import com.dirtyredz.gvmled.light.connection.LightScanner
import com.dirtyredz.gvmled.light.connection.LightTransportFactory
import com.dirtyredz.gvmled.light.connection.LightDiscovery
import com.dirtyredz.gvmled.data.local.LightCatalogPreferences
import com.dirtyredz.gvmled.data.local.WantedStatePreferences
import com.dirtyredz.gvmled.data.repository.LightControlRepository
import com.dirtyredz.gvmled.light.control.CctRange
import com.dirtyredz.gvmled.light.control.LightSynchronizers
import com.dirtyredz.gvmled.data.repository.LightCatalogRepository
import com.dirtyredz.gvmled.data.remote.ApkDownloader
import com.dirtyredz.gvmled.data.remote.GitHubReleaseClient
import com.dirtyredz.gvmled.data.remote.HttpsRequests
import com.dirtyredz.gvmled.data.remote.UrlConnectionConnector
import com.dirtyredz.gvmled.data.repository.LightsRepository
import com.dirtyredz.gvmled.update.UpdateController
import com.dirtyredz.gvmled.update.UpdateFiles
import com.dirtyredz.gvmled.update.android.ApkInspector
import com.dirtyredz.gvmled.update.android.InstallLauncher
import com.dirtyredz.gvmled.update.android.InstalledAppReader
import com.dirtyredz.gvmled.update.android.ReleaseContractLoader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Explicit dependency construction (no DI framework). One instance per process, owned by
 * [GvmApplication]; the activity and ViewModels take what they need from here.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) }

    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    val radio = BluetoothRadio(app, adapter)
    private val scanner: BleScanner? = adapter?.let(::BleScanner)

    private val catalogStore = LightCatalogPreferences(app.getSharedPreferences("lights", Context.MODE_PRIVATE))
    val catalog = LightCatalogRepository(catalogStore, Dispatchers.IO)

    val discovery = LightDiscovery(
        scanner = scanner ?: LightScanner { emptyFlow() },
        monotonicMillis = SystemClock::elapsedRealtime,
        log = log,
    )

    // Without an adapter the radio stays Unavailable, so neither fallback is ever used.
    val coordinator = ConnectionCoordinator(
        addresses = catalog.catalog.map { it.addresses }.stateIn(scope, SharingStarted.Eagerly, catalog.catalog.value.addresses),
        discovery = discovery,
        transports = LightTransportFactory { address ->
            GattConnection(app, checkNotNull(scanner) { "No Bluetooth adapter" }.deviceFor(address))
        },
        radio = radio.state,
        scope = scope,
        monotonicMillis = SystemClock::elapsedRealtime,
        log = log,
    )

    /** The only lifecycle input for light work; the activity reports foreground/background here. */
    val visibility = AppVisibility().also { seam ->
        scope.launch { seam.foreground.collect { if (it) coordinator.start() else coordinator.stop() } }
    }

    val control = LightControlRepository(
        store = WantedStatePreferences(app.getSharedPreferences("wanted", Context.MODE_PRIVATE)),
        io = Dispatchers.IO,
        catalog = catalog.catalog,
        ranges = { coordinator.lights.value.associate { it.address to it.hello?.let(CctRange::of) } },
    )

    val synchronizers = LightSynchronizers(
        sessions = coordinator.activeSessions,
        wanted = control.wanted,
        seed = control::seed,
        scope = scope,
        log = log,
    )

    val lights = LightsRepository(coordinator.lights, catalog.catalog, control.wanted, synchronizers.statuses)

    // Updates: user-initiated only. Nothing here touches the network until Check is tapped.
    val releaseContract = ReleaseContractLoader.load(app, log)
    val installLauncher = InstallLauncher(app)
    private val http = HttpsRequests(
        connector = UrlConnectionConnector(),
        requestHosts = setOf(GitHubReleaseClient.API_HOST, GitHubReleaseClient.WEB_HOST),
        redirectHosts = GitHubReleaseClient.ASSET_HOSTS + GitHubReleaseClient.WEB_HOST,
    )
    private val userAgent = "gvm-led-android"
    val updates = UpdateController(
        contract = releaseContract,
        installedApp = InstalledAppReader(app),
        source = GitHubReleaseClient(http, releaseContract?.latestReleaseApi.orEmpty(), userAgent),
        downloads = ApkDownloader(http, userAgent, releaseContract?.maxApkBytes ?: 0),
        files = UpdateFiles(File(app.cacheDir, UpdateFiles.DIRECTORY)),
        inspector = ApkInspector(app.packageManager),
        permission = installLauncher,
        sdkInt = Build.VERSION.SDK_INT,
        scope = scope,
        io = Dispatchers.IO,
    ).also { it.restore() }

    private companion object {
        const val LOG_TAG = "GvmLed"
    }
}
