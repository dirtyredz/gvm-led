package com.dirtyredz.gvmled.update

/** Why direct installation is not offered for an otherwise valid newer release. */
enum class InstallBlock {
    /** A `.debug` build: a stable APK is a different app, not an update. */
    DebugBuild,
    /** The installed app is not signed by the pinned release key, so Android would refuse the update. */
    SignerMismatch,
}

/** A validated newer release. [manifestBytes] is kept so a ready download can be revalidated later. */
data class ReleaseOffer(
    val versionName: String,
    val versionCode: Long,
    val pageUrl: String,
    val apk: ReleaseAsset,
    val manifest: PhoneReleaseManifest,
    val manifestBytes: ByteArray,
    val block: InstallBlock?,
) {
    override fun equals(other: Any?): Boolean =
        other is ReleaseOffer && versionCode == other.versionCode && pageUrl == other.pageUrl && block == other.block &&
            apk == other.apk && manifest == other.manifest && manifestBytes.contentEquals(other.manifestBytes)

    override fun hashCode(): Int = versionCode.hashCode() * 31 + manifest.hashCode()
}

/** Why GitHub's latest release cannot be used, as far as the contract is concerned. */
enum class ReleaseProblem {
    /** Latest release is not an `android-v<version>` tag. */
    ForeignTag,
    /** A draft or prerelease came back as latest. */
    NotPublished,
    /** Missing or ill-typed fields, too many assets, URLs outside the contract. */
    MalformedRelease,
    DuplicateAsset,
    /** `phone-release.json` exists but breaks the schema, identity or signer pin. */
    ManifestInvalid,
    /** The manifest names a different version than the tag, or its APK asset is missing or differs. */
    ManifestMismatch,
    /** No signer pin is bundled in this build. */
    NoPin,
}

/** Why a download did not become installable. */
enum class DownloadProblem { Interrupted, Network, TooLarge, Integrity, WrongArchive, Storage }

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Available(val offer: ReleaseOffer) : UpdateStatus
    data class UpToDate(val versionName: String, val pageUrl: String) : UpdateStatus
    data class InstalledNewer(val latestVersionName: String, val pageUrl: String) : UpdateStatus
    data class Incompatible(val versionName: String, val minSdk: Int, val pageUrl: String) : UpdateStatus
    /** A release exists but has no update metadata: browser hand-off only, never "current". */
    data class MetadataUnavailable(val tag: String, val pageUrl: String) : UpdateStatus
    data class InvalidRelease(val problem: ReleaseProblem, val pageUrl: String?) : UpdateStatus
    data class CheckFailed(val problem: NetworkProblem?, val retryAtMillis: Long?) : UpdateStatus
    data class Downloading(val offer: ReleaseOffer, val bytes: Long) : UpdateStatus
    data class Verifying(val versionName: String) : UpdateStatus
    data class DownloadFailed(val offer: ReleaseOffer?, val problem: DownloadProblem) : UpdateStatus
    /**
     * A verified APK is waiting. [installerOpened] means Android's installer was shown and the app is
     * still on the old version (cancelled or failed): offer Install again, never "Updated".
     */
    data class ReadyToInstall(
        val versionName: String,
        val pageUrl: String,
        val installerOpened: Boolean = false,
        val permissionDenied: Boolean = false,
    ) : UpdateStatus
    /** The installed version read back from Android is the downloaded one (or newer). */
    data class Updated(val versionName: String) : UpdateStatus
}

/** A check's result kept with the time it was obtained, shown as dated after a later failure. */
data class TimedStatus(val status: UpdateStatus, val atMillis: Long)

data class UpdateState(
    val installed: InstalledApp?,
    val status: UpdateStatus = UpdateStatus.Idle,
    /** The last check that reached GitHub, for display when the latest attempt failed. */
    val previous: TimedStatus? = null,
    /** False when the bundled release contract could not be loaded: checks cannot run. */
    val contractLoaded: Boolean = true,
)
