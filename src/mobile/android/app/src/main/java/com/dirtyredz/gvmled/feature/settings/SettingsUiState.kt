package com.dirtyredz.gvmled.feature.settings

import com.dirtyredz.gvmled.update.DownloadProblem
import com.dirtyredz.gvmled.update.InstallBlock
import com.dirtyredz.gvmled.update.InstalledApp
import com.dirtyredz.gvmled.update.NetworkProblem
import com.dirtyredz.gvmled.update.ReleaseProblem
import com.dirtyredz.gvmled.update.UpdateState
import com.dirtyredz.gvmled.update.UpdateStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The buttons the update card can show; the screen maps each to one controller call. */
enum class UpdateAction(val label: String) {
    Check("Check for updates"),
    DownloadAndInstall("Download and install"),
    RetryDownload("Retry download"),
    Install("Install"),
    InstallAgain("Install again"),
    AllowInstalls("Allow installs"),
    OpenReleasePage("Open release page"),
}

data class SettingsUiState(
    val version: String = "",
    val packageName: String = "",
    val isDebugBuild: Boolean = false,
    val update: UpdateCard = UpdateCard("", null, emptyList()),
)

/** [progress] is 0..1 while downloading. [actions] are in display order; the first is primary. */
data class UpdateCard(
    val message: String,
    val detail: String?,
    val actions: List<UpdateAction>,
    val progress: Float? = null,
    val busy: Boolean = false,
)

fun UpdateState.toSettingsUiState(sdkInt: Int, zone: ZoneId, developmentPackage: String?): SettingsUiState {
    val app = installed
    return SettingsUiState(
        version = app?.let { "${it.versionName} (code ${it.versionCode})" } ?: "Unknown",
        packageName = app?.packageName.orEmpty(),
        isDebugBuild = app != null && app.packageName == developmentPackage,
        update = if (!contractLoaded) {
            UpdateCard("Update checks are unavailable: this build has no readable release contract.", null, emptyList())
        } else {
            updateCard(status, app, sdkInt, zone).withPrevious(this, sdkInt, zone)
        },
    )
}

private val TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ROOT)

private fun time(millis: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

/** After a failed check, the last result that reached GitHub is shown as dated, never as fresh. */
private fun UpdateCard.withPrevious(state: UpdateState, sdkInt: Int, zone: ZoneId): UpdateCard {
    val previous = state.previous ?: return this
    if (state.status !is UpdateStatus.CheckFailed) return this
    val summary = updateCard(previous.status, state.installed, sdkInt, zone).message
    return copy(detail = "Last successful check (${time(previous.atMillis, zone)}): $summary")
}

internal fun updateCard(status: UpdateStatus, installed: InstalledApp?, sdkInt: Int, zone: ZoneId): UpdateCard {
    val check = UpdateAction.Check
    val page = UpdateAction.OpenReleasePage
    return when (status) {
        UpdateStatus.Idle -> UpdateCard("Check GitHub for a newer signed release.", null, listOf(check))
        UpdateStatus.Checking -> UpdateCard("Checking GitHub…", null, emptyList(), busy = true)
        is UpdateStatus.Available -> when (status.offer.block) {
            null -> UpdateCard("Version ${status.offer.versionName} is available.", null, listOf(UpdateAction.DownloadAndInstall, page))
            InstallBlock.DebugBuild -> UpdateCard(
                "Release ${status.offer.versionName} is on GitHub.",
                "This is a debug build; a release APK is a different app, not an update. Use the release page.",
                listOf(page, check),
            )
            InstallBlock.SignerMismatch -> UpdateCard(
                "Version ${status.offer.versionName} is available.",
                "This installation is not signed with the GVM LED release key, so Android would refuse the update.",
                listOf(page, check),
            )
        }
        is UpdateStatus.UpToDate -> UpdateCard("You have the latest version (${status.versionName}).", null, listOf(check))
        is UpdateStatus.InstalledNewer ->
            UpdateCard("This app is newer than the latest release (${status.latestVersionName}).", null, listOf(check))
        is UpdateStatus.Incompatible -> UpdateCard(
            "Version ${status.versionName} needs Android API ${status.minSdk}; this phone has API $sdkInt.", null, listOf(page, check),
        )
        is UpdateStatus.MetadataUnavailable ->
            UpdateCard("Release found; update metadata unavailable.", "Install it from the release page.", listOf(page, check))
        is UpdateStatus.InvalidRelease -> UpdateCard(
            "The latest release can't be installed from here.",
            releaseProblem(status.problem),
            listOfNotNull(page.takeIf { status.pageUrl != null }, check),
        )
        is UpdateStatus.CheckFailed -> UpdateCard(checkFailure(status.problem, status.retryAtMillis, zone), null, listOf(check))
        is UpdateStatus.Downloading -> {
            val total = status.offer.manifest.apkSize
            UpdateCard(
                "Downloading ${status.offer.versionName}… ${megabytes(status.bytes)} of ${megabytes(total)}",
                null, emptyList(), progress = (status.bytes.toFloat() / total).coerceIn(0f, 1f), busy = true,
            )
        }
        is UpdateStatus.Verifying -> UpdateCard("Verifying ${status.versionName}…", null, emptyList(), busy = true)
        is UpdateStatus.DownloadFailed -> UpdateCard(
            downloadProblem(status.problem),
            null,
            listOfNotNull(UpdateAction.RetryDownload.takeIf { status.offer != null && status.offer.block == null }, page, check),
        )
        is UpdateStatus.ReadyToInstall -> when {
            status.permissionDenied -> UpdateCard(
                "Android needs your permission for GVM LED to install updates.",
                "Allow it on the settings page that opens, or install from the release page.",
                listOf(UpdateAction.AllowInstalls, page),
            )
            status.installerOpened -> UpdateCard(
                "Version ${status.versionName} was not installed.",
                "You are still on ${installed?.versionName ?: "the previous version"}. The verified download is kept.",
                listOf(UpdateAction.InstallAgain, page),
            )
            else -> UpdateCard("Version ${status.versionName} is downloaded and verified.", null, listOf(UpdateAction.Install, page))
        }
        is UpdateStatus.Updated -> UpdateCard("Updated to ${status.versionName}.", null, listOf(check))
    }
}

private fun releaseProblem(problem: ReleaseProblem) = when (problem) {
    ReleaseProblem.ForeignTag -> "GitHub's latest release is not an Android release."
    ReleaseProblem.NotPublished -> "GitHub's latest release is a draft or prerelease."
    ReleaseProblem.MalformedRelease -> "The release listing doesn't match the release contract."
    ReleaseProblem.DuplicateAsset -> "The release lists the same file twice."
    ReleaseProblem.ManifestInvalid -> "Its update metadata is malformed or not signed by the pinned key."
    ReleaseProblem.ManifestMismatch -> "Its update metadata doesn't match the release's version or APK."
    ReleaseProblem.NoPin -> "This build has no pinned release signer."
}

private fun checkFailure(problem: NetworkProblem?, retryAt: Long?, zone: ZoneId) = when (problem) {
    null -> "The check stopped when the app left the screen."
    NetworkProblem.Offline -> "Couldn't reach GitHub. Check the phone's connection."
    NetworkProblem.Timeout -> "GitHub didn't answer in time."
    NetworkProblem.RateLimited -> "GitHub's request limit was reached." +
        (retryAt?.let { " Try again after ${time(it, zone)}." } ?: " Try again later.")
    NetworkProblem.NoRelease -> "No release was found on GitHub."
    NetworkProblem.Server -> "GitHub had a server problem. Try again later."
    NetworkProblem.Refused, NetworkProblem.TooLarge, NetworkProblem.Integrity -> "GitHub's response was not accepted."
}

private fun downloadProblem(problem: DownloadProblem) = when (problem) {
    DownloadProblem.Interrupted -> "The download stopped when the app left the screen."
    DownloadProblem.Network -> "The download failed. Check the phone's connection."
    DownloadProblem.TooLarge -> "The download was larger than the release says; it was deleted."
    DownloadProblem.Integrity -> "The download didn't match the release checksum; it was deleted."
    DownloadProblem.WrongArchive -> "The downloaded APK isn't the expected signed release; it was deleted."
    DownloadProblem.Storage -> "The download couldn't be stored."
}

private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
