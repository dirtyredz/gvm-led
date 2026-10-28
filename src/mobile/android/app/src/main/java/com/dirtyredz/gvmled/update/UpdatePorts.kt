package com.dirtyredz.gvmled.update

import com.dirtyredz.gvmled.data.json.JsonObject
import java.io.File

/** The running app's identity as Android reports it. [signers] are lowercase SHA-256 certificate digests. */
data class InstalledApp(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signers: Set<String>,
)

/** Metadata read from a downloaded APK before handing it to the installer. */
data class ArchiveIdentity(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val signers: Set<String>,
)

/** A release asset as listed by GitHub; [url] has already been checked against the contract. */
data class ReleaseAsset(val name: String, val size: Long, val url: String)

/** Why a network step failed, in the terms the Settings screen explains. */
enum class NetworkProblem { Offline, Timeout, RateLimited, NoRelease, Server, Refused, TooLarge, Integrity }

class UpdateNetworkException(
    val problem: NetworkProblem,
    message: String,
    /** Wall-clock time GitHub says a retry may succeed, when it said. */
    val retryAtMillis: Long? = null,
) : Exception(message)

/** GitHub release metadata (`data/remote/GitHubReleaseClient`). Throws [UpdateNetworkException]. */
interface ReleaseSource {
    suspend fun latestRelease(): JsonObject

    /** A small asset's complete bytes, never more than [maxBytes]. */
    suspend fun smallAsset(asset: ReleaseAsset, maxBytes: Long): ByteArray
}

/** Streams an APK to [destination], enforcing size and digest; deletes it on any failure. */
fun interface ApkDownloads {
    suspend fun download(
        asset: ReleaseAsset,
        expectedSize: Long,
        expectedSha256: String,
        destination: File,
        progress: (Long) -> Unit,
    )
}

/** Platform reads (`update/android`). */
fun interface InstalledAppSource {
    fun read(): InstalledApp
}

fun interface ApkInspection {
    /** The archive's identity, or null when Android cannot read it or its signer. */
    fun inspect(file: File): ArchiveIdentity?
}

fun interface InstallPermission {
    fun canRequestInstalls(): Boolean
}

/** What the activity must launch for the controller. Each effect is delivered once. */
sealed interface UpdateEffect {
    data class LaunchInstaller(val apk: File) : UpdateEffect
    data object OpenInstallPermission : UpdateEffect
    data class OpenReleasePage(val url: String) : UpdateEffect
}
