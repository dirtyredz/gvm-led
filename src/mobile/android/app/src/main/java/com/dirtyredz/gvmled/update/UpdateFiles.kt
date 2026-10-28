package com.dirtyredz.gvmled.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Lowercase hex, the form the release contract uses for digests. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * The dedicated update directory (the only path FileProvider exposes, `res/xml/update_paths.xml`).
 * Downloads stream into [partFile]; only a verified file is promoted to [readyApk], with the manifest
 * that verified it beside it so a later launch can revalidate before reuse.
 */
class UpdateFiles(private val directory: File) {
    val partFile: File get() = File(directory, "download.apk.part")
    val readyApk: File get() = File(directory, READY_APK)
    private val readyManifest: File get() = File(directory, "ready-manifest.json")

    /** Removes anything a killed process or cancelled download left behind. */
    fun cleanIncomplete() {
        partFile.delete()
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        if (readyApk.exists() != readyManifest.exists()) clearReady()
    }

    fun prepare(): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create the update folder")
        partFile.delete()
        return partFile
    }

    fun readReadyManifest(): ByteArray? =
        if (readyApk.isFile && readyManifest.isFile) readyManifest.readBytes() else null

    /** Records the verifying manifest, then renames the verified part file into place. */
    fun promote(manifestBytes: ByteArray) {
        val manifestTemp = File(directory, "ready-manifest.json.tmp")
        manifestTemp.writeBytes(manifestBytes)
        readyApk.delete()
        if (!manifestTemp.renameTo(readyManifest) || !partFile.renameTo(readyApk)) {
            clearReady()
            throw IOException("Cannot store the verified download")
        }
    }

    /** True when the ready APK still has exactly the size and SHA-256 its manifest promised. */
    fun readyMatches(size: Long, sha256: String): Boolean {
        val file = readyApk
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex() == sha256
    }

    fun clearReady() {
        readyApk.delete()
        readyManifest.delete()
    }

    companion object {
        /** Subdirectory of the app cache shared through FileProvider. Keep in step with update_paths.xml. */
        const val DIRECTORY = "updates"
        const val READY_APK = "ready.apk"
    }
}
