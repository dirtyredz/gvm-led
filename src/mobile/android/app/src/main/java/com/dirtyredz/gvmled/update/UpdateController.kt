package com.dirtyredz.gvmled.update

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The owner-initiated update flow: check GitHub's latest release, download and verify the signed
 * APK, then ask the activity to open Android's installer. Process-scoped (rotation keeps the task);
 * every network step runs only after a tap and is cancelled when the app leaves the foreground.
 * Success is only ever established by reading the installed version back from Android.
 *
 * All public calls and state changes happen on [scope]'s (main) dispatcher; blocking work on [io].
 */
class UpdateController(
    private val contract: ReleaseContract?,
    private val installedApp: InstalledAppSource,
    private val source: ReleaseSource,
    private val downloads: ApkDownloads,
    private val files: UpdateFiles,
    private val inspector: ApkInspection,
    private val permission: InstallPermission,
    private val sdkInt: Int,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(
        UpdateState(installed = runCatching { installedApp.read() }.getOrNull(), contractLoaded = contract != null),
    )
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val effectChannel = Channel<UpdateEffect>(Channel.BUFFERED)

    /** One-shot requests for the activity; buffered while it is recreated. */
    val effects: Flow<UpdateEffect> = effectChannel.receiveAsFlow()

    private var job: Job? = null
    private val status: UpdateStatus get() = mutableState.value.status

    /** Process start: drop incomplete files and keep only a ready download that still verifies. */
    fun restore() = launchTask(onError = { null }) {
        val contract = contract ?: return@launchTask
        val restored = withContext(io) {
            files.cleanIncomplete()
            val installed = installedApp.read()
            val bytes = files.readReadyManifest() ?: return@withContext null
            val manifest = PhoneReleaseManifest.parseOrNull(bytes, contract)
            when {
                manifest == null -> null
                installed.packageName == contract.packageName && installed.versionCode >= manifest.versionCode ->
                    UpdateStatus.Updated(installed.versionName)
                readyVerifies(manifest, installed, contract) ->
                    UpdateStatus.ReadyToInstall(manifest.versionName, contract.releasePage(contract.tag(manifest.versionName)))
                else -> null
            }.also { if (it !is UpdateStatus.ReadyToInstall) files.clearReady() }
        }
        restored?.let(::setStatus)
    }

    /** Foreground entry: the installed version may have changed while the app was away. */
    fun refreshInstalled() {
        val installed = runCatching { installedApp.read() }.getOrNull() ?: return
        mutableState.update { it.copy(installed = installed) }
    }

    fun check() {
        val contract = contract ?: return
        if (job?.isActive == true) return
        setStatus(UpdateStatus.Checking)
        launchTask(onError = { UpdateStatus.CheckFailed(null, null) }) {
            val result = try {
                withContext(io) { checkLatest(contract) }
            } catch (failure: UpdateNetworkException) {
                UpdateStatus.CheckFailed(failure.problem, failure.retryAtMillis)
            } catch (failure: IOException) {
                UpdateStatus.CheckFailed(NetworkProblem.Offline, null)
            }
            mutableState.update { current ->
                val reached = result !is UpdateStatus.CheckFailed
                current.copy(status = result, previous = if (reached) TimedStatus(result, wallMillis()) else current.previous)
            }
        }
    }

    /** "Download and install": only from a directly installable offer. */
    fun download() {
        val contract = contract ?: return
        val offer = (status as? UpdateStatus.Available)?.offer
            ?: (status as? UpdateStatus.DownloadFailed)?.offer
            ?: return
        if (offer.block != null || job?.isActive == true) return
        setStatus(UpdateStatus.Downloading(offer, 0))
        launchTask(onError = { UpdateStatus.DownloadFailed(offer, DownloadProblem.Storage) }) {
            val outcome = try {
                withContext(io) { fetchAndVerify(offer, contract) }
            } catch (failure: UpdateNetworkException) {
                UpdateStatus.DownloadFailed(offer, downloadProblem(failure.problem))
            } catch (failure: IOException) {
                UpdateStatus.DownloadFailed(offer, DownloadProblem.Network)
            }
            setStatus(outcome)
            if (outcome is UpdateStatus.ReadyToInstall) requestInstall(contract)
        }
    }

    /** "Install" / "Install again": revalidate the ready file, then hand it to Android. */
    fun install() {
        val contract = contract ?: return
        if (status !is UpdateStatus.ReadyToInstall || job?.isActive == true) return
        launchTask(onError = { UpdateStatus.DownloadFailed(null, DownloadProblem.Integrity) }) { requestInstall(contract) }
    }

    /** Returned from the install-source settings screen. Never re-prompts on its own after a denial. */
    fun installPermissionReturned() {
        val ready = status as? UpdateStatus.ReadyToInstall ?: return
        if (permission.canRequestInstalls()) install() else setStatus(ready.copy(permissionDenied = true))
    }

    /** Returned from Android's installer: only the installed version decides whether it worked. */
    fun installerReturned() {
        val ready = status as? UpdateStatus.ReadyToInstall ?: return
        refreshInstalled()
        val installed = mutableState.value.installed
        val readyCode = contract?.let { readyVersionCode(it) }
        if (installed != null && readyCode != null && installed.versionCode >= readyCode) {
            files.clearReady()
            setStatus(UpdateStatus.Updated(installed.versionName))
        } else {
            setStatus(ready.copy(installerOpened = true))
        }
    }

    fun openReleasePage() {
        val url = pageUrlOf(status) ?: contract?.releasePageBase ?: return
        effectChannel.trySend(UpdateEffect.OpenReleasePage(url))
    }

    /** Background entry: unfinished network work stops; returning offers Retry. */
    fun onBackground() {
        val running = job?.takeIf { it.isActive } ?: return
        when (val current = status) {
            UpdateStatus.Checking -> {
                running.cancel()
                setStatus(UpdateStatus.CheckFailed(null, null))
            }
            is UpdateStatus.Downloading -> {
                running.cancel()
                setStatus(UpdateStatus.DownloadFailed(current.offer, DownloadProblem.Interrupted))
            }
            else -> Unit // verifying or restoring finishes quickly and touches no network
        }
    }

    private suspend fun checkLatest(contract: ReleaseContract): UpdateStatus {
        if (contract.certificateSha256 == null) return UpdateStatus.InvalidRelease(ReleaseProblem.NoPin, null)
        val release = when (val selection = ReleasePolicy.select(source.latestRelease(), contract)) {
            is ReleaseSelection.Rejected -> return UpdateStatus.InvalidRelease(selection.problem, selection.pageUrl)
            is ReleaseSelection.Found -> selection.release
        }
        val manifestAsset = release.manifest ?: return UpdateStatus.MetadataUnavailable(release.tag, release.pageUrl)
        if (manifestAsset.size > contract.maxManifestBytes)
            return UpdateStatus.InvalidRelease(ReleaseProblem.ManifestInvalid, release.pageUrl)
        val bytes = source.smallAsset(manifestAsset, contract.maxManifestBytes)
        val manifest = PhoneReleaseManifest.parseOrNull(bytes, contract)
            ?: return UpdateStatus.InvalidRelease(ReleaseProblem.ManifestInvalid, release.pageUrl)
        val installed = installedApp.read()
        mutableState.update { it.copy(installed = installed) }
        return ReleasePolicy.evaluate(release, manifest, bytes, installed, sdkInt, contract)
    }

    private suspend fun fetchAndVerify(offer: ReleaseOffer, contract: ReleaseContract): UpdateStatus {
        files.clearReady()
        val part = files.prepare()
        try {
            downloads.download(offer.apk, offer.manifest.apkSize, offer.manifest.apkSha256, part) { bytes ->
                // Late progress must never overwrite an interruption or a result.
                mutableState.update {
                    if (it.status is UpdateStatus.Downloading) it.copy(status = UpdateStatus.Downloading(offer, bytes)) else it
                }
            }
        } catch (failure: Throwable) {
            part.delete() // cancelled or failed: nothing partial survives
            throw failure
        }
        setStatus(UpdateStatus.Verifying(offer.versionName))
        val installed = installedApp.read()
        val archive = inspector.inspect(part)
        if (archive == null || !ReleasePolicy.archiveMatches(archive, offer.manifest, installed, contract)) {
            part.delete()
            return UpdateStatus.DownloadFailed(offer, DownloadProblem.WrongArchive)
        }
        try {
            files.promote(offer.manifestBytes)
        } catch (_: IOException) {
            part.delete()
            return UpdateStatus.DownloadFailed(offer, DownloadProblem.Storage)
        }
        return UpdateStatus.ReadyToInstall(offer.versionName, offer.pageUrl)
    }

    private suspend fun requestInstall(contract: ReleaseContract) {
        val ready = status as? UpdateStatus.ReadyToInstall ?: return
        val verified = withContext(io) {
            val manifest = files.readReadyManifest()?.let { PhoneReleaseManifest.parseOrNull(it, contract) }
            manifest != null && readyVerifies(manifest, installedApp.read(), contract)
        }
        if (!verified) {
            withContext(io) { files.clearReady() }
            setStatus(UpdateStatus.DownloadFailed(null, DownloadProblem.Integrity))
            return
        }
        if (!permission.canRequestInstalls()) {
            effectChannel.trySend(UpdateEffect.OpenInstallPermission)
            return
        }
        setStatus(ready.copy(permissionDenied = false))
        effectChannel.trySend(UpdateEffect.LaunchInstaller(files.readyApk))
    }

    /** Size, digest and archive identity of the ready file, checked again before every reuse. */
    private fun readyVerifies(manifest: PhoneReleaseManifest, installed: InstalledApp, contract: ReleaseContract): Boolean {
        if (!files.readyMatches(manifest.apkSize, manifest.apkSha256)) return false
        val archive = inspector.inspect(files.readyApk) ?: return false
        return ReleasePolicy.archiveMatches(archive, manifest, installed, contract)
    }

    private fun readyVersionCode(contract: ReleaseContract): Long? =
        files.readReadyManifest()?.let { PhoneReleaseManifest.parseOrNull(it, contract) }?.versionCode

    /** Runs one task; a platform surprise becomes [onError]'s status (when non-null), never a crash. */
    private fun launchTask(onError: () -> UpdateStatus?, block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onError()?.let(::setStatus)
            }
        }
    }

    private fun setStatus(next: UpdateStatus) = mutableState.update { it.copy(status = next) }

    private fun downloadProblem(problem: NetworkProblem) = when (problem) {
        NetworkProblem.TooLarge -> DownloadProblem.TooLarge
        NetworkProblem.Integrity -> DownloadProblem.Integrity
        else -> DownloadProblem.Network
    }

    private fun pageUrlOf(status: UpdateStatus): String? = when (status) {
        is UpdateStatus.Available -> status.offer.pageUrl
        is UpdateStatus.UpToDate -> status.pageUrl
        is UpdateStatus.InstalledNewer -> status.pageUrl
        is UpdateStatus.Incompatible -> status.pageUrl
        is UpdateStatus.MetadataUnavailable -> status.pageUrl
        is UpdateStatus.InvalidRelease -> status.pageUrl
        is UpdateStatus.Downloading -> status.offer.pageUrl
        is UpdateStatus.DownloadFailed -> status.offer?.pageUrl
        is UpdateStatus.ReadyToInstall -> status.pageUrl
        else -> null
    }
}
