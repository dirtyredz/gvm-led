package com.dirtyredz.gvmled.data.remote

import com.dirtyredz.gvmled.update.NetworkProblem
import com.dirtyredz.gvmled.update.ReleaseAsset
import com.dirtyredz.gvmled.update.UpdateNetworkException
import com.dirtyredz.gvmled.update.toHex
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    private val apk = "abcd".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).toHex()
    private val asset = ReleaseAsset(
        "GVM-LED-0.1.2-android.apk",
        4,
        "https://github.com/dirtyredz/gvm-led/releases/download/android-v0.1.2/GVM-LED-0.1.2-android.apk",
    )
    private val destination by lazy { temp.newFile("download.apk.part") }

    /** github.com redirects to the asset CDN, which serves [stream] with the [declared] length. */
    private fun serving(stream: InputStream, declared: Long) = FakeConnector { url ->
        if (url.host == "github.com") {
            FakeResponse(302, mapOf("Location" to "https://release-assets.githubusercontent.com/x"))
        } else {
            FakeResponse(200, contentLength = declared, body = stream)
        }
    }

    private fun downloader(connector: HttpConnector, maxApkBytes: Long = 1_000) = ApkDownloader(
        HttpsRequests(
            connector,
            setOf("api.github.com", "github.com"),
            setOf("release-assets.githubusercontent.com", "github.com"),
        ),
        "gvm-led-test",
        maxApkBytes,
    )

    private suspend fun download(
        body: ByteArray = apk,
        declared: Long = body.size.toLong(),
        stream: InputStream = ByteArrayInputStream(body),
        expectedSize: Long = 4,
        expectedSha: String = sha,
        connector: FakeConnector = serving(stream, declared),
        progress: (Long) -> Unit = {},
    ) = downloader(connector).download(asset, expectedSize, expectedSha, destination, progress)

    private suspend fun assertFails(problem: NetworkProblem, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $problem")
        } catch (e: UpdateNetworkException) {
            assertEquals(problem, e.problem)
        }
        assertFalse(destination.exists())
    }

    @Test
    fun `writes the exact bytes and reports progress to the size`() = runTest {
        val seen = mutableListOf<Long>()
        val connector = serving(ByteArrayInputStream(apk), 4)
        download(connector = connector, progress = { seen += it })
        assertArrayEquals(apk, destination.readBytes())
        assertEquals(4L, seen.last())
        assertEquals(seen.sorted(), seen)
        assertEquals("application/octet-stream", connector.requestHeaders.first()["Accept"])
        assertEquals(asset.url, connector.opened.first())
    }

    @Test
    fun `declared length differing from the manifest is an integrity failure`() = runTest {
        assertFails(NetworkProblem.Integrity) { download(declared = 5) }
    }

    @Test
    fun `body longer than expected is too large even when undeclared`() = runTest {
        assertFails(NetworkProblem.TooLarge) { download(body = "abcde".toByteArray(), declared = -1) }
    }

    @Test
    fun `truncated body is an integrity failure`() = runTest {
        assertFails(NetworkProblem.Integrity) { download(body = "abc".toByteArray(), declared = -1) }
    }

    @Test
    fun `wrong digest is an integrity failure`() = runTest {
        assertFails(NetworkProblem.Integrity) { download(expectedSha = "0".repeat(64)) }
    }

    @Test
    fun `expected size outside the contract fails before any request`() = runTest {
        val connector = serving(ByteArrayInputStream(apk), 4)
        destination.writeText("stale")
        assertFails(NetworkProblem.TooLarge) { download(expectedSize = 0, connector = connector) }
        destination.writeText("stale")
        assertFails(NetworkProblem.TooLarge) { download(expectedSize = 1_001, connector = connector) }
        assertTrue(connector.opened.isEmpty())
    }

    @Test
    fun `network failure mid stream is offline and deletes the file`() = runTest {
        val broken = object : InputStream() {
            private var first = true
            override fun read(): Int = throw IOException("reset")

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!first) throw IOException("connection reset")
                first = false
                b[off] = 'a'.code.toByte()
                return 1
            }
        }
        assertFails(NetworkProblem.Offline) { download(stream = broken, declared = 4) }
    }
}
