package com.dirtyredz.gvmled.data.remote

import com.dirtyredz.gvmled.update.NetworkProblem
import com.dirtyredz.gvmled.update.ReleaseAsset
import com.dirtyredz.gvmled.update.ReleaseListing
import com.dirtyredz.gvmled.update.UpdateNetworkException
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GitHubReleaseClientTest {
    private val api = "https://api.github.com/repos/dirtyredz/gvm-led/releases/latest"

    private fun client(json: String): GitHubReleaseClient {
        val bytes = json.toByteArray()
        val connector = FakeConnector {
            FakeResponse(200, contentLength = bytes.size.toLong(), body = ByteArrayInputStream(bytes))
        }
        val http = HttpsRequests(connector, setOf("api.github.com"), emptySet())
        return GitHubReleaseClient(http, api, "test-agent")
    }

    @Test fun latestReleaseDecodesGitHubJsonIntoTheTypedPortModel() = runTest {
        val listing = client(
            """{"tag_name":"android-v0.1.2","html_url":"https://github.com/dirtyredz/gvm-led/releases/tag/android-v0.1.2","draft":false,"prerelease":false,"assets":[{"name":"phone-release.json","size":400,"browser_download_url":"https://github.com/dirtyredz/gvm-led/releases/download/android-v0.1.2/phone-release.json"}]}""",
        ).latestRelease()

        assertEquals(
            ReleaseListing(
                tag = "android-v0.1.2",
                pageUrl = "https://github.com/dirtyredz/gvm-led/releases/tag/android-v0.1.2",
                draft = false,
                prerelease = false,
                assets = listOf(
                    ReleaseAsset(
                        "phone-release.json",
                        400,
                        "https://github.com/dirtyredz/gvm-led/releases/download/android-v0.1.2/phone-release.json",
                    ),
                ),
            ),
            listing,
        )
    }

    @Test fun malformedGitHubShapeIsRefusedByTheRemoteAdapter() = runTest {
        try {
            client("""{"tag_name":"android-v0.1.2","assets":[]}""").latestRelease()
            fail("Expected UpdateNetworkException")
        } catch (failure: UpdateNetworkException) {
            assertEquals(NetworkProblem.Refused, failure.problem)
        }
    }
}
