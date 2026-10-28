package com.dirtyredz.gvmled.update.android

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.dirtyredz.gvmled.update.ApkInspection
import com.dirtyredz.gvmled.update.ArchiveIdentity
import java.io.File

/**
 * Preflight read of a downloaded APK's package, version, minimum SDK and signer. Android's installer
 * still performs the full verification; this only stops the app offering an APK it can already tell
 * is wrong. Unreadable metadata or an unknown signer returns null (fail closed).
 */
class ApkInspector(private val packageManager: PackageManager) : ApkInspection {
    override fun inspect(file: File): ArchiveIdentity? {
        val info = archiveInfo(file, signingFlags()) ?: return null
        // Some releases leave archive SigningInfo null; legacy signatures are the fallback.
        val withSigners = if (info.signerDigests().isEmpty()) archiveInfo(file, legacyFlags()) ?: info else info
        val signers = withSigners.signerDigests().ifEmpty { return null }
        val minSdk = info.applicationInfo?.minSdkVersion ?: return null
        return ArchiveIdentity(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCodeCompat(),
            minSdk = minSdk,
            signers = signers,
        )
    }

    private fun archiveInfo(file: File, flags: Int): PackageInfo? =
        runCatching { packageManager.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun legacyFlags(): Int = PackageManager.GET_SIGNATURES
}
