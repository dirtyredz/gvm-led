package com.dirtyredz.gvmled.data.remote

import com.dirtyredz.gvmled.data.json.Json
import com.dirtyredz.gvmled.data.json.JsonException
import com.dirtyredz.gvmled.data.json.JsonObject
import com.dirtyredz.gvmled.update.NetworkProblem
import com.dirtyredz.gvmled.update.ReleaseAsset
import com.dirtyredz.gvmled.update.ReleaseSource
import com.dirtyredz.gvmled.update.UpdateNetworkException

/**
 * Unauthenticated reads of the public GitHub release API: the latest release of one repository and
 * small assets such as `phone-release.json`. No token is ever sent or requested.
 * [GitHub release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)
 */
class GitHubReleaseClient(
    private val http: HttpsRequests,
    private val latestReleaseUrl: String,
    private val userAgent: String,
) : ReleaseSource {
    override suspend fun latestRelease(): JsonObject {
        val response = http.get(latestReleaseUrl, apiHeaders())
        val bytes = http.readBounded(response, MAX_RELEASE_BYTES)
        return try {
            Json.parseObject(bytes.toString(Charsets.UTF_8))
        } catch (_: JsonException) {
            throw UpdateNetworkException(NetworkProblem.Refused, "GitHub returned malformed release data")
        }
    }

    override suspend fun smallAsset(asset: ReleaseAsset, maxBytes: Long): ByteArray =
        http.readBounded(http.get(asset.url, assetHeaders(userAgent)), maxBytes)

    private fun apiHeaders() = mapOf(
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to API_VERSION,
        "User-Agent" to userAgent,
    )

    companion object {
        const val API_VERSION = "2022-11-28"
        private const val MAX_RELEASE_BYTES = 512L * 1024

        const val API_HOST = "api.github.com"
        const val WEB_HOST = "github.com"
        /** Where `github.com/.../releases/download/...` redirects asset downloads. */
        val ASSET_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

        fun assetHeaders(userAgent: String) = mapOf("Accept" to "application/octet-stream", "User-Agent" to userAgent)
    }
}
