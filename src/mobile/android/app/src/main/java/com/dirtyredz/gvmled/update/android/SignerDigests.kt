package com.dirtyredz.gvmled.update.android

import com.dirtyredz.gvmled.update.toHex
import java.security.MessageDigest

/**
 * Which certificates identify a package, by API level, as lowercase SHA-256 digests of their DER
 * bytes (the form `signing-certificate.json` pins). API 28+ reads `SigningInfo.apkContentsSigners`
 * (the current signer set, also for multi-signer APKs); API 26-27 only has the legacy `signatures`.
 * On 28+ a missing `SigningInfo` falls back to `signatures` (some archive reads leave it null).
 * An empty result means "unknown signer" and never matches a pin.
 */
object SignerDigests {
    fun select(sdkInt: Int, current: List<ByteArray>?, legacy: List<ByteArray>?): Set<String> {
        val chosen = if (sdkInt >= 28) current ?: legacy else legacy
        return chosen.orEmpty().map(::digest).toSet()
    }

    fun digest(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).toHex()
}
