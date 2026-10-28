package com.dirtyredz.gvmled.update

import com.dirtyredz.gvmled.update.UpdateFixtures.archive
import com.dirtyredz.gvmled.update.UpdateFixtures.asset
import com.dirtyredz.gvmled.update.UpdateFixtures.contract
import com.dirtyredz.gvmled.update.UpdateFixtures.installed
import com.dirtyredz.gvmled.update.UpdateFixtures.releaseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePolicyTest {
    private fun found(json: com.dirtyredz.gvmled.data.json.JsonObject) =
        (ReleasePolicy.select(json, contract) as ReleaseSelection.Found).release

    private fun problem(json: com.dirtyredz.gvmled.data.json.JsonObject) =
        (ReleasePolicy.select(json, contract) as ReleaseSelection.Rejected).problem

    private fun manifest(code: Long = 3, minSdk: Int = 26, size: Long = 4): Pair<PhoneReleaseManifest, ByteArray> {
        val bytes = UpdateFixtures.manifestJson(versionCode = code, minSdk = minSdk, size = size).toByteArray()
        return PhoneReleaseManifest.parseOrNull(bytes, contract)!! to bytes
    }

    private fun evaluate(release: LatestRelease, code: Long = 3, installedApp: InstalledApp = installed(), sdk: Int = 34, minSdk: Int = 26): UpdateStatus {
        val (parsed, bytes) = manifest(code, minSdk)
        return ReleasePolicy.evaluate(release, parsed, bytes, installedApp, sdk, contract)
    }

    @Test fun selectsTheManifestAndTheSignedApkNeverTheUnsigned() {
        val tag = "android-v0.1.2"
        val release = found(
            releaseJson(assets = listOf(
                asset(tag, "GVM-LED-0.1.2-android-unsigned.apk", 4),
                asset(tag, "android-provenance.json", 500),
                asset(tag, "phone-release.json", 400),
                asset(tag, "GVM-LED-0.1.2-android.apk", 4),
            )),
        )
        assertEquals("0.1.2", release.versionName)
        assertEquals("GVM-LED-0.1.2-android.apk", release.apk?.name)
        assertEquals("https://github.com/dirtyredz/gvm-led/releases/download/android-v0.1.2/phone-release.json", release.manifest?.url)
        assertEquals("https://github.com/dirtyredz/gvm-led/releases/tag/android-v0.1.2", release.pageUrl)

        val unsignedOnly = found(releaseJson(assets = listOf(asset(tag, "phone-release.json", 400), asset(tag, "GVM-LED-0.1.2-android-unsigned.apk", 4))))
        assertNull(unsignedOnly.apk)
    }

    @Test fun foreignDraftAndPrereleaseTagsAreContractErrorsNotUpToDate() {
        assertEquals(ReleaseProblem.ForeignTag, problem(releaseJson(tag = "v0.1.2")))
        assertEquals(ReleaseProblem.ForeignTag, problem(releaseJson(tag = "android-v0.1.2-rc1")))
        assertEquals(ReleaseProblem.NotPublished, problem(releaseJson(draft = true)))
        assertEquals(ReleaseProblem.NotPublished, problem(releaseJson(prerelease = true)))
    }

    @Test fun duplicateAssetsForeignUrlsAndTooManyAssetsAreRefused() {
        val tag = "android-v0.1.2"
        assertEquals(ReleaseProblem.DuplicateAsset,
            problem(releaseJson(assets = listOf(asset(tag, "phone-release.json", 400), asset(tag, "phone-release.json", 400)))))
        val foreign = """{"name":"phone-release.json","size":400,"browser_download_url":"https://github.com/evil/gvm-led/releases/download/$tag/phone-release.json"}"""
        assertEquals(ReleaseProblem.MalformedRelease, problem(releaseJson(assets = listOf(foreign))))
        val otherTag = asset("android-v0.1.1", "GVM-LED-0.1.2-android.apk", 4)
        assertEquals(ReleaseProblem.MalformedRelease, problem(releaseJson(assets = listOf(otherTag))))
        assertEquals(ReleaseProblem.MalformedRelease, problem(releaseJson(assets = (1..21).map { asset(tag, "file$it", 1) })))
    }

    @Test fun aMissingManifestIsKeptAsMissingNotInvented() {
        val tag = "android-v0.1.2"
        val release = found(releaseJson(assets = listOf(asset(tag, "GVM-LED-0.1.2-android.apk", 4))))
        assertNull(release.manifest)
    }

    @Test fun versionCodesDecideHigherEqualAndLower() {
        val release = found(releaseJson(versionCode = 3))
        val offer = (evaluate(release, installedApp = installed(versionCode = 2)) as UpdateStatus.Available).offer
        assertEquals(3L, offer.versionCode)
        assertNull(offer.block)
        assertEquals(UpdateStatus.UpToDate("0.1.2", release.pageUrl), evaluate(release, installedApp = installed(versionCode = 3)))
        assertEquals(UpdateStatus.InstalledNewer("0.1.2", release.pageUrl), evaluate(release, installedApp = installed(versionCode = 4)))
    }

    @Test fun anIncompatibleSdkGetsItsOwnResult() {
        val release = found(releaseJson())
        assertEquals(UpdateStatus.Incompatible("0.1.2", 30, release.pageUrl), evaluate(release, sdk = 29, minSdk = 30))
    }

    @Test fun manifestMustMatchTheTagAndTheListedApk() {
        val release = found(releaseJson(versionCode = 3))
        assertEquals(ReleaseProblem.ManifestMismatch, (evaluate(release, code = 4) as UpdateStatus.InvalidRelease).problem)
        val noApk = found(releaseJson(assets = listOf(asset("android-v0.1.2", "phone-release.json", 400))))
        assertEquals(ReleaseProblem.ManifestMismatch, (evaluate(noApk) as UpdateStatus.InvalidRelease).problem)
        val otherSize = found(releaseJson(apkSize = 5))
        assertEquals(ReleaseProblem.ManifestMismatch, (evaluate(otherSize) as UpdateStatus.InvalidRelease).problem)
    }

    @Test fun debugBuildsAndForeignSignersAreCheckOnly() {
        val release = found(releaseJson())
        val debug = installed(versionCode = 1, packageName = "com.dirtyredz.gvmled.debug", signers = setOf("d".repeat(64)))
        assertEquals(InstallBlock.DebugBuild, (evaluate(release, installedApp = debug) as UpdateStatus.Available).offer.block)
        val resigned = installed(signers = setOf(UpdateFixtures.OTHER_PIN))
        assertEquals(InstallBlock.SignerMismatch, (evaluate(release, installedApp = resigned) as UpdateStatus.Available).offer.block)
    }

    @Test fun archivePreflightRequiresPackageVersionSdkAndPinnedSigner() {
        val (parsed, _) = manifest()
        assertTrue(ReleasePolicy.archiveMatches(archive(), parsed, installed(), contract))
        assertFalse(ReleasePolicy.archiveMatches(archive(packageName = "com.dirtyredz.gvmled.debug"), parsed, installed(), contract))
        assertFalse(ReleasePolicy.archiveMatches(archive(versionCode = 4), parsed, installed(), contract))
        assertFalse(ReleasePolicy.archiveMatches(archive(minSdk = 28), parsed, installed(), contract))
        assertFalse(ReleasePolicy.archiveMatches(archive(signers = setOf(UpdateFixtures.OTHER_PIN)), parsed, installed(), contract))
        assertFalse(ReleasePolicy.archiveMatches(archive(signers = setOf(UpdateFixtures.PIN, UpdateFixtures.OTHER_PIN)), parsed, installed(), contract))
        assertFalse(ReleasePolicy.archiveMatches(archive(signers = emptySet()), parsed, installed(), contract))
        // No downgrade or reinstall, and never over an installation signed by another key.
        assertFalse(ReleasePolicy.archiveMatches(archive(), parsed, installed(versionCode = 3), contract))
        assertFalse(ReleasePolicy.archiveMatches(archive(), parsed, installed(signers = setOf(UpdateFixtures.OTHER_PIN)), contract))
    }
}
