package com.dirtyredz.gvmled.data.remote

import com.dirtyredz.gvmled.update.ApkDownloads
import com.dirtyredz.gvmled.update.NetworkProblem
import com.dirtyredz.gvmled.update.ReleaseAsset
import com.dirtyredz.gvmled.update.UpdateNetworkException
import com.dirtyredz.gvmled.update.toHex
import java.io.File
import java.security.MessageDigest

/**
 * Streams a release APK into a private part file. The expected size (from the validated manifest)
 * is the hard ceiling while streaming; the final byte count and SHA-256 must match exactly. The
 * part file is deleted on any failure or cancellation, so nothing partial is ever installable.
 */
class ApkDownloader(
    private val http: HttpsRequests,
    private val userAgent: String,
    private val maxApkBytes: Long,
) : ApkDownloads {
    override suspend fun download(
        asset: ReleaseAsset,
        expectedSize: Long,
        expectedSha256: String,
        destination: File,
        progress: (Long) -> Unit,
    ) {
        var complete = false
        try {
            if (expectedSize !in 1..maxApkBytes) throw UpdateNetworkException(NetworkProblem.TooLarge, "APK size outside the contract")
            http.get(asset.url, GitHubReleaseClient.assetHeaders(userAgent)).use { response ->
                if (response.contentLength >= 0 && response.contentLength != expectedSize)
                    throw UpdateNetworkException(NetworkProblem.Integrity, "Declared size differs from the manifest")
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                val total = destination.outputStream().use { out ->
                    http.copyBounded(response.body, expectedSize) { buffer, count ->
                        out.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        written += count
                        progress(written)
                    }
                }
                if (total != expectedSize) throw UpdateNetworkException(NetworkProblem.Integrity, "Download was truncated")
                if (digest.digest().toHex() != expectedSha256)
                    throw UpdateNetworkException(NetworkProblem.Integrity, "SHA-256 differs from the manifest")
            }
            complete = true
        } finally {
            if (!complete) destination.delete()
        }
    }
}
