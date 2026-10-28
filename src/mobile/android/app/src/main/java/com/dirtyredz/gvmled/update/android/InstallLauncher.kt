package com.dirtyredz.gvmled.update.android

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dirtyredz.gvmled.update.InstallPermission
import java.io.File

/**
 * Builds the system hand-offs for the update flow: Android's installer for the verified APK (a
 * FileProvider content URI with a temporary read grant; no storage permission), this app's
 * install-source settings page, and the release page in a browser.
 */
class InstallLauncher(private val context: Context) : InstallPermission {
    override fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installerIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, providerAuthority(context.packageName), apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    fun releasePageIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, url.toUri())

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"

        /** Matches the manifest's `${applicationId}.updates` provider authority. */
        fun providerAuthority(packageName: String) = "$packageName.updates"
    }
}
