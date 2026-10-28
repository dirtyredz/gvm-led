package com.dirtyredz.gvmled.update

import com.dirtyredz.gvmled.data.json.JsonObject

/** GitHub's latest release after shape checks: the assets the app may use, with contract URLs. */
data class LatestRelease(
    val tag: String,
    val versionName: String,
    val pageUrl: String,
    val manifest: ReleaseAsset?,
    val apk: ReleaseAsset?,
)

sealed interface ReleaseSelection {
    data class Found(val release: LatestRelease) : ReleaseSelection
    data class Rejected(val problem: ReleaseProblem, val pageUrl: String?) : ReleaseSelection
}

/**
 * Pure release rules. Only a published, non-prerelease `android-v` release counts; exactly one
 * manifest and the exact signed APK name are used (never the unsigned APK); asset URLs must be the
 * contract's own repository/tag/name; version comparison uses versionCode only.
 */
object ReleasePolicy {
    fun select(release: JsonObject, contract: ReleaseContract): ReleaseSelection {
        val tag = release.string("tag_name") ?: return rejected(ReleaseProblem.MalformedRelease)
        val versionName = contract.tagVersion(tag) ?: return rejected(ReleaseProblem.ForeignTag)
        val pageUrl = contract.releasePage(tag)
        if (release.string("html_url") != pageUrl) return rejected(ReleaseProblem.MalformedRelease)
        if (release.bool("draft") != false || release.bool("prerelease") != false)
            return rejected(ReleaseProblem.NotPublished, pageUrl)
        val listed = release.array("assets")?.items ?: return rejected(ReleaseProblem.MalformedRelease, pageUrl)
        if (listed.size > contract.maxAssetsPerRelease) return rejected(ReleaseProblem.MalformedRelease, pageUrl)
        val assets = mutableListOf<ReleaseAsset>()
        for (item in listed) {
            val asset = item as? JsonObject ?: return rejected(ReleaseProblem.MalformedRelease, pageUrl)
            val name = asset.string("name") ?: return rejected(ReleaseProblem.MalformedRelease, pageUrl)
            val size = asset.long("size")?.takeIf { it >= 0 } ?: return rejected(ReleaseProblem.MalformedRelease, pageUrl)
            assets += ReleaseAsset(name, size, asset.string("browser_download_url").orEmpty())
        }
        if (assets.groupBy { it.name }.any { it.value.size > 1 }) return rejected(ReleaseProblem.DuplicateAsset, pageUrl)
        fun named(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
        val manifest = named(contract.manifestAsset)
        val apk = named(contract.apkName(versionName))
        for (asset in listOfNotNull(manifest, apk)) {
            if (asset.url != contract.assetUrl(tag, asset.name)) return rejected(ReleaseProblem.MalformedRelease, pageUrl)
        }
        return ReleaseSelection.Found(LatestRelease(tag, versionName, pageUrl, manifest, apk))
    }

    /** The check result for a release whose manifest validated. */
    fun evaluate(
        release: LatestRelease,
        manifest: PhoneReleaseManifest,
        manifestBytes: ByteArray,
        installed: InstalledApp,
        sdkInt: Int,
        contract: ReleaseContract,
    ): UpdateStatus {
        val apk = release.apk
        if (manifest.versionName != release.versionName || apk == null || apk.size != manifest.apkSize)
            return UpdateStatus.InvalidRelease(ReleaseProblem.ManifestMismatch, release.pageUrl)
        if (manifest.minSdk > sdkInt) return UpdateStatus.Incompatible(manifest.versionName, manifest.minSdk, release.pageUrl)
        return when {
            manifest.versionCode == installed.versionCode && installed.packageName == contract.packageName ->
                UpdateStatus.UpToDate(manifest.versionName, release.pageUrl)
            manifest.versionCode < installed.versionCode && installed.packageName == contract.packageName ->
                UpdateStatus.InstalledNewer(manifest.versionName, release.pageUrl)
            else -> UpdateStatus.Available(
                ReleaseOffer(
                    versionName = manifest.versionName,
                    versionCode = manifest.versionCode,
                    pageUrl = release.pageUrl,
                    apk = apk,
                    manifest = manifest,
                    manifestBytes = manifestBytes,
                    block = installBlock(installed, contract),
                ),
            )
        }
    }

    /** Why this installation cannot take a direct update, or null when it can. */
    fun installBlock(installed: InstalledApp, contract: ReleaseContract): InstallBlock? = when {
        installed.packageName != contract.packageName -> InstallBlock.DebugBuild
        contract.certificateSha256 == null || installed.signers != setOf(contract.certificateSha256) ->
            InstallBlock.SignerMismatch
        else -> null
    }

    /**
     * Preflight on a downloaded APK whose bytes already matched [manifest]. Android's installer still
     * performs the full signature and upgrade checks; this refuses anything it would be wrong to offer.
     */
    fun archiveMatches(
        archive: ArchiveIdentity,
        manifest: PhoneReleaseManifest,
        installed: InstalledApp,
        contract: ReleaseContract,
    ): Boolean {
        val pin = contract.certificateSha256 ?: return false
        return installBlock(installed, contract) == null &&
            archive.packageName == contract.packageName &&
            archive.packageName == installed.packageName &&
            archive.versionCode == manifest.versionCode &&
            archive.versionName == manifest.versionName &&
            archive.minSdk == manifest.minSdk &&
            archive.signers == setOf(pin) &&
            manifest.certificateSha256 == pin &&
            archive.versionCode > installed.versionCode
    }

    private fun rejected(problem: ReleaseProblem, pageUrl: String? = null) = ReleaseSelection.Rejected(problem, pageUrl)
}
