package com.dirtyredz.gvmled.feature.settings

import com.dirtyredz.gvmled.update.DownloadProblem
import com.dirtyredz.gvmled.update.InstallBlock
import com.dirtyredz.gvmled.update.NetworkProblem
import com.dirtyredz.gvmled.update.PhoneReleaseManifest
import com.dirtyredz.gvmled.update.ReleaseAsset
import com.dirtyredz.gvmled.update.ReleaseOffer
import com.dirtyredz.gvmled.update.TimedStatus
import com.dirtyredz.gvmled.update.UpdateFixtures
import com.dirtyredz.gvmled.update.UpdateFixtures.installed
import com.dirtyredz.gvmled.update.UpdateState
import com.dirtyredz.gvmled.update.UpdateStatus
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStateTest {
    private val debugPackage = "com.dirtyredz.gvmled.debug"
    private val zone = ZoneOffset.UTC
    private val retryAt = 1_700_000_000_000L // 14 Nov 2023 22:13:20 UTC

    private fun offer(block: InstallBlock? = null): ReleaseOffer {
        val bytes = UpdateFixtures.manifestJson().toByteArray()
        val manifest = PhoneReleaseManifest.parseOrNull(bytes, UpdateFixtures.contract)!!
        val asset = ReleaseAsset(manifest.apkName, manifest.apkSize, "https://github.com/dirtyredz/gvm-led/releases/download/x/${manifest.apkName}")
        return ReleaseOffer(manifest.versionName, manifest.versionCode, "https://github.com/page", asset, manifest, bytes, block)
    }

    private fun card(status: UpdateStatus, installedApp: com.dirtyredz.gvmled.update.InstalledApp? = installed()) =
        updateCard(status, installedApp, 34, zone)

    private fun ui(state: UpdateState) = state.toSettingsUiState(34, zone, debugPackage)

    @Test
    fun `shows the version line and flags debug builds`() {
        val release = ui(UpdateState(installed(versionCode = 2)))
        assertEquals("0.1.1 (code 2)", release.version)
        assertFalse(release.isDebugBuild)
        assertTrue(ui(UpdateState(installed(packageName = debugPackage))).isDebugBuild)
        assertEquals("Unknown", ui(UpdateState(null)).version)
    }

    @Test
    fun `missing contract offers no actions`() {
        val card = ui(UpdateState(installed(), contractLoaded = false)).update
        assertTrue(card.actions.isEmpty())
        assertTrue(card.message.startsWith("Update checks are unavailable"))
    }

    @Test
    fun `idle offers a check`() {
        assertEquals(listOf(UpdateAction.Check), card(UpdateStatus.Idle).actions)
    }

    @Test
    fun `available offer is installable unless blocked`() {
        assertEquals(UpdateAction.DownloadAndInstall, card(UpdateStatus.Available(offer())).actions.first())
        val debug = card(UpdateStatus.Available(offer(InstallBlock.DebugBuild)))
        assertFalse(UpdateAction.DownloadAndInstall in debug.actions)
        assertTrue(UpdateAction.OpenReleasePage in debug.actions)
        val signer = card(UpdateStatus.Available(offer(InstallBlock.SignerMismatch)))
        assertFalse(UpdateAction.DownloadAndInstall in signer.actions)
    }

    @Test
    fun `missing metadata is a browser hand off`() {
        val card = card(UpdateStatus.MetadataUnavailable("android-v0.1.2", "https://github.com/page"))
        assertEquals("Release found; update metadata unavailable.", card.message)
        assertTrue(UpdateAction.OpenReleasePage in card.actions)
        assertFalse(UpdateAction.DownloadAndInstall in card.actions)
    }

    @Test
    fun `rate limited failure names the retry time`() {
        val card = card(UpdateStatus.CheckFailed(NetworkProblem.RateLimited, retryAt))
        assertTrue(card.message, "14 Nov 22:13" in card.message)
    }

    @Test
    fun `an older result is dated after a failed check`() {
        val state = UpdateState(
            installed(),
            UpdateStatus.CheckFailed(NetworkProblem.Offline, null),
            previous = TimedStatus(UpdateStatus.UpToDate("0.1.1", "https://github.com/page"), retryAt),
        )
        val card = ui(state).update
        assertTrue(card.detail.orEmpty(), card.detail!!.startsWith("Last successful check ("))
        assertTrue("14 Nov 22:13" in card.detail!!)
        assertFalse("latest version" in card.message)
        assertNull(ui(state.copy(previous = null)).update.detail)
    }

    @Test
    fun `downloading reports a progress fraction`() {
        val card = card(UpdateStatus.Downloading(offer(), 1))
        assertEquals(0.25f, card.progress!!, 0.001f)
        assertTrue(card.busy)
        assertTrue(card.actions.isEmpty())
    }

    @Test
    fun `installer opened never claims the update happened`() {
        val card = card(UpdateStatus.ReadyToInstall("0.1.2", "https://github.com/page", installerOpened = true))
        assertEquals(UpdateAction.InstallAgain, card.actions.first())
        assertTrue(card.message.contains("was not installed"))
        assertFalse(card.message.contains("Updated"))
    }

    @Test
    fun `denied permission leads with allow installs`() {
        val card = card(UpdateStatus.ReadyToInstall("0.1.2", "https://github.com/page", permissionDenied = true))
        assertEquals(UpdateAction.AllowInstalls, card.actions.first())
    }

    @Test
    fun `ready and updated states`() {
        assertEquals(UpdateAction.Install, card(UpdateStatus.ReadyToInstall("0.1.2", "u")).actions.first())
        assertEquals("Updated to 0.1.2.", card(UpdateStatus.Updated("0.1.2")).message)
    }

    @Test
    fun `failed download retries only when installable`() {
        assertEquals(UpdateAction.RetryDownload, card(UpdateStatus.DownloadFailed(offer(), DownloadProblem.Network)).actions.first())
        val blocked = card(UpdateStatus.DownloadFailed(offer(InstallBlock.DebugBuild), DownloadProblem.Network))
        assertFalse(UpdateAction.RetryDownload in blocked.actions)
        assertNotNull(blocked.message)
    }
}
