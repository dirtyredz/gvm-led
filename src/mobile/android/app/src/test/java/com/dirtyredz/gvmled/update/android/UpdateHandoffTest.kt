package com.dirtyredz.gvmled.update.android

import com.dirtyredz.gvmled.update.UpdateFiles
import com.dirtyredz.gvmled.update.toHex
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element

class UpdateHandoffTest {
    @get:Rule val temp = TemporaryFolder()

    private val android = "http://schemas.android.com/apk/res/android"
    private val legacy = listOf(byteArrayOf(1, 2, 3))
    private val current = listOf(byteArrayOf(9, 8, 7))

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun xml(path: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File(path))
    }

    @Test
    fun `api 26 and 27 use legacy signatures only`() {
        for (sdk in listOf(26, 27)) {
            assertEquals(setOf(sha(legacy[0])), SignerDigests.select(sdk, current, legacy))
        }
    }

    @Test
    fun `api 28 plus prefers current and falls back to legacy`() {
        assertEquals(setOf(sha(current[0])), SignerDigests.select(28, current, legacy))
        assertEquals(setOf(sha(current[0])), SignerDigests.select(35, current, null))
        assertEquals(setOf(sha(legacy[0])), SignerDigests.select(28, null, legacy))
    }

    @Test
    fun `unknown signer is empty and digest is lowercase hex`() {
        assertTrue(SignerDigests.select(30, null, null).isEmpty())
        assertTrue(SignerDigests.select(30, emptyList(), null).isEmpty())
        val der = byteArrayOf(-1, 0, 127, -128)
        val digest = SignerDigests.digest(der)
        assertEquals(sha(der), digest)
        assertEquals(64, digest.length)
        assertEquals(digest.lowercase(), digest)
    }

    @Test
    fun `provider authority and mime`() {
        assertEquals("com.dirtyredz.gvmled.updates", InstallLauncher.providerAuthority("com.dirtyredz.gvmled"))
        assertEquals("application/vnd.android.package-archive", InstallLauncher.APK_MIME)
    }

    @Test
    fun `file provider exposes only the update folder`() {
        val paths = xml("src/main/res/xml/update_paths.xml").documentElement
        val children = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(1, children.size)
        assertEquals("cache-path", children[0].tagName)
        assertEquals(UpdateFiles.DIRECTORY + "/", children[0].getAttribute("path"))
    }

    @Test
    fun `manifest keeps the provider private and requests only install permissions`() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val provider = manifest.getElementsByTagName("provider").item(0) as Element
        assertEquals("false", provider.getAttributeNS(android, "exported"))
        assertEquals("true", provider.getAttributeNS(android, "grantUriPermissions"))
        assertEquals("\${applicationId}.updates", provider.getAttributeNS(android, "authorities"))

        val permissions = (0 until manifest.getElementsByTagName("uses-permission").length)
            .map { (manifest.getElementsByTagName("uses-permission").item(it) as Element).getAttributeNS(android, "name") }
        assertTrue("android.permission.INTERNET" in permissions)
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
        assertFalse(permissions.any { it.endsWith("WRITE_EXTERNAL_STORAGE") || it.endsWith("READ_EXTERNAL_STORAGE") })
    }

    @Test
    fun `promote moves the part file into place and keeps the manifest`() {
        val files = UpdateFiles(File(temp.root, "updates"))
        files.prepare().writeBytes("apk".toByteArray())
        files.promote("manifest".toByteArray())
        assertFalse(files.partFile.exists())
        assertArrayEquals("apk".toByteArray(), files.readyApk.readBytes())
        assertArrayEquals("manifest".toByteArray(), files.readReadyManifest())
    }

    @Test
    fun `readyMatches checks size and digest`() {
        val files = UpdateFiles(File(temp.root, "updates"))
        val bytes = "abcd".toByteArray()
        files.prepare().writeBytes(bytes)
        files.promote(byteArrayOf())
        assertTrue(files.readyMatches(4, sha(bytes)))
        assertFalse(files.readyMatches(5, sha(bytes)))
        files.readyApk.writeBytes("abce".toByteArray())
        assertFalse(files.readyMatches(4, sha(bytes)))
    }

    @Test
    fun `cleanIncomplete removes the part file and an orphaned ready apk`() {
        val directory = File(temp.root, "updates")
        val files = UpdateFiles(directory)
        files.prepare().writeText("partial")
        files.readyApk.writeText("orphan")
        files.cleanIncomplete()
        assertFalse(files.partFile.exists())
        assertFalse(files.readyApk.exists())
        assertNull(files.readReadyManifest())
    }
}
