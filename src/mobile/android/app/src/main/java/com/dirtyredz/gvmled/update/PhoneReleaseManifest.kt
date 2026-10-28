package com.dirtyredz.gvmled.update

import com.dirtyredz.gvmled.data.json.Json
import com.dirtyredz.gvmled.data.json.JsonException
import com.dirtyredz.gvmled.data.json.JsonObject

/**
 * A validated schema-1 `phone-release.json`, mirroring `validatePhoneManifest` in
 * `src/mobile/releases/manifest.js`: exact fields, the contract's repository/channel/package, the
 * pinned signer (a manifest can never choose its own), the version pairing and a bounded exact APK.
 */
data class PhoneReleaseManifest(
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val commit: String,
    val certificateSha256: String,
    val apkName: String,
    val apkSize: Long,
    val apkSha256: String,
) {
    companion object {
        /** The manifest in [bytes], or null when it breaks any contract rule. */
        fun parseOrNull(bytes: ByteArray, contract: ReleaseContract): PhoneReleaseManifest? {
            if (bytes.size > contract.maxManifestBytes) return null
            val value = try {
                Json.parseObject(bytes.toString(Charsets.UTF_8))
            } catch (_: JsonException) {
                return null
            }
            return validate(value, contract)
        }

        private fun validate(value: JsonObject, contract: ReleaseContract): PhoneReleaseManifest? {
            val pin = contract.certificateSha256 ?: return null
            if (value.keys != contract.manifestFields) return null
            val apk = value.obj("apk") ?: return null
            if (apk.keys != contract.apkFields) return null
            val versionCode = value.long("versionCode") ?: return null
            val versionName = value.string("versionName") ?: return null
            val minSdk = value.long("minSdk") ?: return null
            val commit = value.string("commit")
            val apkSize = apk.long("size") ?: return null
            val apkSha = apk.string("sha256")
            val valid = value.long("schema") == 1L &&
                value.string("repository") == contract.repository &&
                value.string("channel") == contract.channel &&
                value.string("packageName") == contract.packageName &&
                value.string("certificateSha256") == pin &&
                versionCode > contract.bootstrapVersionCode &&
                versionCode <= contract.maxVersionCode &&
                versionName == ReleaseVersion.versionNameForCode(versionCode) &&
                minSdk in contract.minSdkFloor..contract.minSdkCeiling &&
                ReleaseContract.isHex(commit, 40) &&
                apk.string("name") == contract.apkName(versionName) &&
                apkSize in 1..contract.maxApkBytes &&
                ReleaseContract.isHex(apkSha, 64)
            if (!valid) return null
            return PhoneReleaseManifest(
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk.toInt(),
                commit = commit!!,
                certificateSha256 = pin,
                apkName = contract.apkName(versionName),
                apkSize = apkSize,
                apkSha256 = apkSha!!,
            )
        }
    }
}
