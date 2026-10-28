package com.dirtyredz.gvmled.update

import com.dirtyredz.gvmled.data.json.Json
import com.dirtyredz.gvmled.data.json.JsonObject
import com.dirtyredz.gvmled.data.json.JsonString

/**
 * The bundled public release contract: `src/mobile/releases/release-contract.json` plus the pinned
 * signer from `signing-certificate.json`, copied into the APK at build time (those files stay
 * authoritative). [certificateSha256] is null when no valid pin for this package is recorded; then
 * no manifest validates and direct installation is impossible.
 */
data class ReleaseContract(
    val repository: String,
    val packageName: String,
    val developmentPackageName: String,
    val channel: String,
    val tagPrefix: String,
    val manifestAsset: String,
    val apkPrefix: String,
    val apkSuffix: String,
    val unsignedApkSuffix: String,
    val manifestFields: Set<String>,
    val apkFields: Set<String>,
    val maxApkBytes: Long,
    val maxManifestBytes: Long,
    val maxVersionCode: Long,
    val minSdkFloor: Int,
    val minSdkCeiling: Int,
    val maxAssetsPerRelease: Int,
    val bootstrapVersionCode: Long,
    val certificateSha256: String?,
) {
    fun apkName(versionName: String) = "$apkPrefix$versionName$apkSuffix"

    fun tag(versionName: String) = "$tagPrefix$versionName"

    /** The versionName of an exact Android tag, or null (only `android-v<semver>` counts). */
    fun tagVersion(tag: String): String? {
        if (!tag.startsWith(tagPrefix)) return null
        val version = tag.removePrefix(tagPrefix)
        return version.takeIf { TAG_VERSION.matches(it) }
    }

    val releasePageBase: String get() = "https://github.com/$repository/releases"

    fun releasePage(tag: String) = "$releasePageBase/tag/$tag"

    fun assetUrl(tag: String, name: String) = "$releasePageBase/download/$tag/$name"

    val latestReleaseApi: String get() = "https://api.github.com/repos/$repository/releases/latest"

    companion object {
        private val TAG_VERSION = Regex("(0|[1-9]\\d{0,9})\\.(0|[1-9]\\d{0,4})\\.(0|[1-9]\\d{0,4})")
        private val HEX64 = Regex("[a-f0-9]{64}")

        fun isHex(value: String?, length: Int): Boolean =
            value != null && value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }

        /** Throws [IllegalArgumentException] when either bundled file is malformed. */
        fun parse(contractJson: String, certificateJson: String): ReleaseContract {
            val contract = Json.parseObject(contractJson)
            val identity = Json.parseObject(certificateJson)
            val assets = contract.obj("assets").required("assets")
            val limits = contract.obj("limits").required("limits")
            val packageName = contract.string("packageName").required("packageName")
            val pin = identity.string("certificateSha256")
                ?.takeIf { HEX64.matches(it) && identity.string("packageName") == packageName }
            require(contract.long("schema") == 1L) { "Unsupported release contract schema" }
            return ReleaseContract(
                repository = contract.string("repository").required("repository"),
                packageName = packageName,
                developmentPackageName = contract.string("developmentPackageName").required("developmentPackageName"),
                channel = contract.string("channel").required("channel"),
                tagPrefix = contract.obj("tags")?.string("android").required("tags.android"),
                manifestAsset = assets.string("manifest").required("assets.manifest"),
                apkPrefix = assets.string("apkPrefix").required("assets.apkPrefix"),
                apkSuffix = assets.string("apkSuffix").required("assets.apkSuffix"),
                unsignedApkSuffix = assets.string("unsignedApkSuffix").required("assets.unsignedApkSuffix"),
                manifestFields = contract.strings("manifestFields"),
                apkFields = contract.strings("apkFields"),
                maxApkBytes = limits.long("maxApkBytes").required("limits.maxApkBytes"),
                maxManifestBytes = limits.long("maxManifestBytes").required("limits.maxManifestBytes"),
                maxVersionCode = limits.long("maxVersionCode").required("limits.maxVersionCode"),
                minSdkFloor = limits.long("minSdkFloor").required("limits.minSdkFloor").toInt(),
                minSdkCeiling = limits.long("minSdkCeiling").required("limits.minSdkCeiling").toInt(),
                maxAssetsPerRelease = limits.long("maxAssetsPerRelease").required("limits.maxAssetsPerRelease").toInt(),
                bootstrapVersionCode = identity.long("bootstrapVersionCode").required("bootstrapVersionCode"),
                certificateSha256 = pin,
            )
        }

        private fun <T> T?.required(name: String): T = requireNotNull(this) { "Release contract is missing $name" }

        private fun JsonObject.strings(key: String): Set<String> =
            array(key).required(key).items.map { (it as? JsonString)?.value.required(key) }.toSet()
    }
}
