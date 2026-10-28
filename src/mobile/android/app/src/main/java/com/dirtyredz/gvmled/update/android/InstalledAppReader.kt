package com.dirtyredz.gvmled.update.android

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.dirtyredz.gvmled.update.InstalledApp
import com.dirtyredz.gvmled.update.InstalledAppSource

/** This app's installed package, version code and signer, read fresh from PackageManager each time. */
class InstalledAppReader(private val context: Context) : InstalledAppSource {
    override fun read(): InstalledApp {
        val info = packageInfo(context.packageManager, context.packageName)
        return InstalledApp(
            packageName = info.packageName,
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCodeCompat(),
            signers = info.signerDigests(),
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(manager: PackageManager, name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 28) {
            manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            manager.getPackageInfo(name, PackageManager.GET_SIGNATURES)
        }
}

@Suppress("DEPRECATION")
internal fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
internal fun PackageInfo.signerDigests(): Set<String> {
    val current = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners?.map { it.toByteArray() } else null
    return SignerDigests.select(Build.VERSION.SDK_INT, current, signatures?.map { it.toByteArray() })
}
