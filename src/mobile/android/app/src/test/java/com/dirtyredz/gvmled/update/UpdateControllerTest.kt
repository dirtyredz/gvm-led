package com.dirtyredz.gvmled.update

import com.dirtyredz.gvmled.data.json.JsonObject
import com.dirtyredz.gvmled.update.UpdateFixtures.archive
import com.dirtyredz.gvmled.update.UpdateFixtures.asset
import com.dirtyredz.gvmled.update.UpdateFixtures.contract
import com.dirtyredz.gvmled.update.UpdateFixtures.installed
import com.dirtyredz.gvmled.update.UpdateFixtures.releaseJson
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private val apkBytes = "abcd".toByteArray()
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).toHex()

    private inner class Rig(scope: TestScope) {
        var installedApp = installed(versionCode = 2)
        var latest: suspend () -> JsonObject = { releaseJson(versionCode = 3) }
        var manifestBytes = UpdateFixtures.manifestJson(versionCode = 3, sha256 = apkSha).toByteArray()
        var downloadGate: CompletableDeferred<Unit>? = null
        var downloadFailure: UpdateNetworkException? = null
        var archiveIdentity: ArchiveIdentity? = archive(versionCode = 3)
        var canInstall = true
        var releaseRequests = 0
        val directory: File = temp.newFolder("updates")
        val files = UpdateFiles(directory)
        val effects = mutableListOf<UpdateEffect>()

        private val source = object : ReleaseSource {
            override suspend fun latestRelease(): JsonObject {
                releaseRequests++
                return latest()
            }
            override suspend fun smallAsset(asset: ReleaseAsset, maxBytes: Long) = manifestBytes
        }

        val controller = UpdateController(
            contract = contract,
            installedApp = { installedApp },
            source = source,
            downloads = { _, _, _, destination, progress ->
                destination.writeBytes(apkBytes.copyOf(2))
                progress(2)
                downloadGate?.await()
                downloadFailure?.let { destination.delete(); throw it }
                destination.writeBytes(apkBytes)
                progress(4)
            },
            files = files,
            inspector = { archiveIdentity },
            permission = { canInstall },
            sdkInt = 34,
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            wallMillis = { 1_000L },
        )

        val status: UpdateStatus get() = controller.state.value.status

        fun collectEffects(scope: TestScope) {
            scope.backgroundScope.launch { controller.effects.collect { effects += it } }
        }
    }

    private fun TestScope.rig() = Rig(this)

    @Test fun offlineTimeoutAndRateLimitAreExplicitAndKeepTheLastResultDated() = runTest {
        val rig = rig()
        rig.controller.check()
        runCurrent()
        val available = rig.status as UpdateStatus.Available
        assertEquals("0.1.2", available.offer.versionName)

        rig.latest = { throw UpdateNetworkException(NetworkProblem.Offline, "no route") }
        rig.controller.check()
        runCurrent()
        assertEquals(UpdateStatus.CheckFailed(NetworkProblem.Offline, null), rig.status)
        assertEquals(TimedStatus(available, 1_000L), rig.controller.state.value.previous)

        rig.latest = { throw UpdateNetworkException(NetworkProblem.Timeout, "slow") }
        rig.controller.check()
        runCurrent()
        assertEquals(UpdateStatus.CheckFailed(NetworkProblem.Timeout, null), rig.status)

        rig.latest = { throw UpdateNetworkException(NetworkProblem.RateLimited, "limit", retryAtMillis = 99_000) }
        rig.controller.check()
        runCurrent()
        assertEquals(UpdateStatus.CheckFailed(NetworkProblem.RateLimited, 99_000), rig.status)
        assertEquals(4, rig.releaseRequests) // one request per tap; nothing retries on its own
    }

    @Test fun repeatedTapsAndReattachmentNeverDuplicateACheck() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        rig.latest = { gate.await(); releaseJson(versionCode = 3) }
        rig.controller.check()
        rig.controller.check()
        runCurrent()
        rig.controller.check()
        assertEquals(UpdateStatus.Checking, rig.status)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, rig.releaseRequests)
        assertTrue(rig.status is UpdateStatus.Available)
    }

    @Test fun leavingTheForegroundCancelsACheckAndALateAnswerIsIgnored() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        rig.latest = { gate.await(); releaseJson(versionCode = 3) }
        rig.controller.check()
        runCurrent()
        rig.controller.onBackground()
        gate.complete(Unit)
        runCurrent()
        assertEquals(UpdateStatus.CheckFailed(null, null), rig.status)
    }

    @Test fun aMissingManifestOffersOnlyTheReleasePage() = runTest {
        val rig = rig()
        rig.latest = { releaseJson(assets = listOf(asset("android-v0.1.2", "GVM-LED-0.1.2-android.apk", 4))) }
        rig.controller.check()
        runCurrent()
        val status = rig.status as UpdateStatus.MetadataUnavailable
        assertEquals("android-v0.1.2", status.tag)
        rig.controller.download()
        runCurrent()
        assertEquals(status, rig.status)
    }

    @Test fun downloadVerifiesThenHandsTheReadyFileToTheInstallerOnce() = runTest {
        val rig = rig()
        rig.collectEffects(this)
        rig.controller.check()
        runCurrent()
        rig.controller.download()
        rig.controller.download()
        runCurrent()

        assertEquals(UpdateStatus.ReadyToInstall("0.1.2", "https://github.com/dirtyredz/gvm-led/releases/tag/android-v0.1.2"), rig.status)
        assertEquals(listOf<UpdateEffect>(UpdateEffect.LaunchInstaller(rig.files.readyApk)), rig.effects)
        assertTrue(rig.files.readyApk.readBytes().contentEquals(apkBytes))
        assertFalse(rig.files.partFile.exists())
    }

    @Test fun installerCancellationOffersInstallAgainAndOnlyTheInstalledVersionMeansUpdated() = runTest {
        val rig = rig()
        rig.collectEffects(this)
        rig.controller.check()
        runCurrent()
        rig.controller.download()
        runCurrent()

        rig.controller.installerReturned() // cancelled: still on 0.1.1
        val again = rig.status as UpdateStatus.ReadyToInstall
        assertTrue(again.installerOpened)

        rig.controller.install()
        runCurrent()
        assertEquals(2, rig.effects.size)

        rig.installedApp = installed(versionCode = 3)
        rig.controller.installerReturned()
        assertEquals(UpdateStatus.Updated("0.1.2"), rig.status)
        assertFalse(rig.files.readyApk.exists())
    }

    @Test fun installPermissionIsRequestedOnceAndDenialLeavesAUsableScreen() = runTest {
        val rig = rig()
        rig.collectEffects(this)
        rig.canInstall = false
        rig.controller.check()
        runCurrent()
        rig.controller.download()
        runCurrent()
        assertEquals(listOf<UpdateEffect>(UpdateEffect.OpenInstallPermission), rig.effects)

        rig.controller.installPermissionReturned() // still denied
        runCurrent()
        assertTrue((rig.status as UpdateStatus.ReadyToInstall).permissionDenied)
        assertEquals(1, rig.effects.size) // no repeated prompt

        rig.controller.openReleasePage()
        runCurrent()
        assertEquals(UpdateEffect.OpenReleasePage("https://github.com/dirtyredz/gvm-led/releases/tag/android-v0.1.2"), rig.effects.last())

        rig.canInstall = true
        rig.controller.installPermissionReturned() // granted on a later visit
        runCurrent()
        assertEquals(UpdateEffect.LaunchInstaller(rig.files.readyApk), rig.effects.last())
    }

    @Test fun leavingDuringADownloadDeletesThePartFileAndOffersRetry() = runTest {
        val rig = rig()
        rig.controller.check()
        runCurrent()
        rig.downloadGate = CompletableDeferred()
        rig.controller.download()
        runCurrent()
        assertTrue(rig.status is UpdateStatus.Downloading)
        assertTrue(rig.files.partFile.exists())

        rig.controller.onBackground()
        runCurrent()
        assertEquals(DownloadProblem.Interrupted, (rig.status as UpdateStatus.DownloadFailed).problem)
        assertFalse(rig.files.partFile.exists())
        assertFalse(rig.files.readyApk.exists())

        rig.downloadGate = null
        rig.controller.download() // Retry download
        runCurrent()
        assertTrue(rig.status is UpdateStatus.ReadyToInstall)
    }

    @Test fun integrityAndArchiveFailuresNeverProduceAnInstallableFile() = runTest {
        val rig = rig()
        rig.collectEffects(this)
        rig.controller.check()
        runCurrent()
        rig.downloadFailure = UpdateNetworkException(NetworkProblem.Integrity, "digest")
        rig.controller.download()
        runCurrent()
        assertEquals(DownloadProblem.Integrity, (rig.status as UpdateStatus.DownloadFailed).problem)

        rig.downloadFailure = null
        rig.archiveIdentity = archive(versionCode = 3, signers = setOf(UpdateFixtures.OTHER_PIN))
        rig.controller.download()
        runCurrent()
        assertEquals(DownloadProblem.WrongArchive, (rig.status as UpdateStatus.DownloadFailed).problem)
        assertFalse(rig.files.readyApk.exists())
        assertTrue(rig.effects.isEmpty())
    }

    @Test fun aReadyFileIsRevalidatedBeforeEveryReuse() = runTest {
        val rig = rig()
        rig.collectEffects(this)
        rig.canInstall = false
        rig.controller.check()
        runCurrent()
        rig.controller.download()
        runCurrent()
        rig.files.readyApk.writeBytes("abce".toByteArray()) // changed while away in settings

        rig.canInstall = true
        rig.controller.installPermissionReturned()
        runCurrent()
        assertEquals(DownloadProblem.Integrity, (rig.status as UpdateStatus.DownloadFailed).problem)
        assertEquals(listOf<UpdateEffect>(UpdateEffect.OpenInstallPermission), rig.effects)
        assertFalse(rig.files.readyApk.exists())
    }

    @Test fun processRestartKeepsOnlyAVerifiedReadyFile() = runTest {
        val first = rig()
        first.canInstall = false
        first.controller.check()
        runCurrent()
        first.controller.download()
        runCurrent()
        first.files.partFile.writeBytes(byteArrayOf(1)) // a killed second download

        val restarted = UpdateController(
            contract, { installed(versionCode = 2) }, NoSource, { _, _, _, _, _ -> error("no download") },
            first.files, { archive(versionCode = 3) }, { true }, 34, backgroundScope, StandardTestDispatcher(testScheduler),
        )
        restarted.restore()
        runCurrent()
        assertTrue(restarted.state.value.status is UpdateStatus.ReadyToInstall)
        assertFalse(first.files.partFile.exists())

        first.files.readyApk.writeBytes("zzzz".toByteArray())
        val tampered = UpdateController(
            contract, { installed(versionCode = 2) }, NoSource, { _, _, _, _, _ -> error("no download") },
            first.files, { archive(versionCode = 3) }, { true }, 34, backgroundScope, StandardTestDispatcher(testScheduler),
        )
        tampered.restore()
        runCurrent()
        assertEquals(UpdateStatus.Idle, tampered.state.value.status)
        assertFalse(first.files.readyApk.exists())
    }

    @Test fun restartAfterASuccessfulInstallReportsTheInstalledVersion() = runTest {
        val first = rig()
        first.canInstall = false
        first.controller.check()
        runCurrent()
        first.controller.download()
        runCurrent()

        val updated = UpdateController(
            contract, { installed(versionCode = 3) }, NoSource, { _, _, _, _, _ -> error("no download") },
            first.files, { archive(versionCode = 3) }, { true }, 34, backgroundScope, StandardTestDispatcher(testScheduler),
        )
        updated.restore()
        runCurrent()
        assertEquals(UpdateStatus.Updated("0.1.2"), updated.state.value.status)
        assertFalse(first.files.readyApk.exists())
    }

    @Test fun debugBuildsNeverDownload() = runTest {
        val rig = rig()
        rig.installedApp = installed(versionCode = 1, packageName = "com.dirtyredz.gvmled.debug", signers = setOf("d".repeat(64)))
        rig.controller.check()
        runCurrent()
        val offer = (rig.status as UpdateStatus.Available).offer
        assertEquals(InstallBlock.DebugBuild, offer.block)
        rig.controller.download()
        runCurrent()
        assertEquals(UpdateStatus.Available(offer), rig.status)
    }

    @Test fun effectsWaitForTheRecreatedActivityAndAreDeliveredOnce() = runTest {
        val rig = rig()
        rig.controller.check()
        runCurrent()
        rig.controller.openReleasePage() // no collector: the activity is being recreated
        val received = mutableListOf<UpdateEffect>()
        val first = backgroundScope.launch { rig.controller.effects.collect { received += it } }
        runCurrent()
        first.cancel()
        backgroundScope.launch { rig.controller.effects.collect { received += it } }
        runCurrent()
        assertEquals(1, received.size)
        assertNotNull(received.single() as? UpdateEffect.OpenReleasePage)
    }

    private object NoSource : ReleaseSource {
        override suspend fun latestRelease(): JsonObject = error("no network in this test")
        override suspend fun smallAsset(asset: ReleaseAsset, maxBytes: Long): ByteArray = error("no network in this test")
    }
}
