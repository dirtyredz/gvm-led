package com.dirtyredz.gvmled.update.android

import android.content.Context
import com.dirtyredz.gvmled.update.ReleaseContract

/**
 * Reads the release contract and signer pin bundled from `src/mobile/releases/` by the
 * `bundleReleaseContract` Gradle task. Null (with the reason logged) disables update checks rather
 * than guessing a contract.
 */
object ReleaseContractLoader {
    const val CONTRACT_ASSET = "release/release-contract.json"
    const val CERTIFICATE_ASSET = "release/signing-certificate.json"

    fun load(context: Context, log: (String) -> Unit): ReleaseContract? = try {
        val assets = context.assets
        val contract = assets.open(CONTRACT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val certificate = assets.open(CERTIFICATE_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        ReleaseContract.parse(contract, certificate)
    } catch (failure: Exception) {
        log("release contract unavailable: $failure")
        null
    }
}
