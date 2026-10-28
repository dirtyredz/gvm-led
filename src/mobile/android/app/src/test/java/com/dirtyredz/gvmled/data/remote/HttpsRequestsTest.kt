package com.dirtyredz.gvmled.data.remote

import com.dirtyredz.gvmled.update.NetworkProblem
import com.dirtyredz.gvmled.update.UpdateNetworkException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpsRequestsTest {
    private val api = "https://api.github.com/repos/x/releases/latest"

    private fun ok(bytes: ByteArray = ByteArray(0), declared: Long = bytes.size.toLong()) =
        FakeResponse(200, contentLength = declared, body = ByteArrayInputStream(bytes))

    private fun redirect(to: String, code: Int = 302) = FakeResponse(code, mapOf("Location" to to))

    private fun requests(connector: HttpConnector, maxRedirects: Int = 4, wall: () -> Long = { 0L }) = HttpsRequests(
        connector,
        setOf("api.github.com", "github.com"),
        setOf("release-assets.githubusercontent.com", "github.com"),
        maxRedirects,
        wall,
    )

    private fun failure(http: HttpsRequests, url: String = api): UpdateNetworkException {
        try {
            http.get(url, emptyMap()).close()
        } catch (e: UpdateNetworkException) {
            return e
        }
        fail("Expected UpdateNetworkException")
        throw AssertionError()
    }

    private fun statusFailure(code: Int, headers: Map<String, String> = emptyMap(), wall: () -> Long = { 0L }) =
        failure(requests(FakeConnector { FakeResponse(code, headers) }, wall = wall))

    @Test
    fun `refuses http and unlisted hosts without opening`() {
        val connector = FakeConnector { ok() }
        val http = requests(connector)
        assertEquals(NetworkProblem.Refused, failure(http, "http://api.github.com/x").problem)
        assertEquals(NetworkProblem.Refused, failure(http, "https://evil.example/x").problem)
        assertEquals(NetworkProblem.Refused, failure(http, "https://api.github.com:8443/x").problem)
        assertEquals(NetworkProblem.Refused, failure(http, "https://user@api.github.com/x").problem)
        assertTrue(connector.opened.isEmpty())
    }

    @Test
    fun `follows absolute and relative redirects`() {
        val connector = FakeConnector.sequence(
            { redirect("https://github.com/a/b/download/x") },
            { redirect("/moved/y", 307) },
            { redirect("https://release-assets.githubusercontent.com/z") },
            { ok() },
        )
        requests(connector).get(api, emptyMap()).close()
        assertEquals(
            listOf(
                api,
                "https://github.com/a/b/download/x",
                "https://github.com/moved/y",
                "https://release-assets.githubusercontent.com/z",
            ),
            connector.opened,
        )
    }

    @Test
    fun `refuses unsafe redirects and closes the redirect response`() {
        val toHttp = redirect("http://github.com/x")
        assertEquals(NetworkProblem.Refused, failure(requests(FakeConnector { toHttp })).problem)
        assertTrue(toHttp.closed)
        // api.github.com is a request host but not a redirect host.
        assertEquals(NetworkProblem.Refused, failure(requests(FakeConnector { redirect("https://api.github.com/x") })).problem)
        assertEquals(NetworkProblem.Refused, failure(requests(FakeConnector { redirect("https://evil.example/x") })).problem)
        assertEquals(NetworkProblem.Refused, failure(requests(FakeConnector { FakeResponse(302) })).problem)
    }

    @Test
    fun `refuses more than maxRedirects hops`() {
        val connector = FakeConnector { redirect("https://github.com/loop") }
        assertEquals(NetworkProblem.Refused, failure(requests(connector, maxRedirects = 2)).problem)
        assertEquals(3, connector.opened.size)
    }

    @Test
    fun `maps status codes to problems`() {
        assertEquals(NetworkProblem.NoRelease, statusFailure(404).problem)
        assertEquals(NetworkProblem.Server, statusFailure(500).problem)
        assertEquals(NetworkProblem.Server, statusFailure(503).problem)
        assertEquals(NetworkProblem.Refused, statusFailure(401).problem)
        assertEquals(NetworkProblem.Refused, statusFailure(403).problem)
        val limited = statusFailure(403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1700000000"))
        assertEquals(NetworkProblem.RateLimited, limited.problem)
        assertEquals(1_700_000_000_000L, limited.retryAtMillis)
        val tooMany = statusFailure(429, mapOf("retry-after" to "60"), wall = { 5_000L })
        assertEquals(NetworkProblem.RateLimited, tooMany.problem)
        assertEquals(65_000L, tooMany.retryAtMillis)
        assertNull(statusFailure(429).retryAtMillis)
    }

    @Test
    fun `failure responses are closed`() {
        val response = FakeResponse(500)
        failure(requests(FakeConnector { response }))
        assertTrue(response.closed)
    }

    @Test
    fun `maps connector exceptions`() {
        fun problem(error: Exception) = failure(requests(FakeConnector { throw error })).problem
        assertEquals(NetworkProblem.Timeout, problem(SocketTimeoutException("slow")))
        assertEquals(NetworkProblem.Offline, problem(UnknownHostException("api.github.com")))
        assertEquals(NetworkProblem.Refused, problem(SSLHandshakeException("bad cert")))
    }

    @Test
    fun `readBounded returns exact bytes and closes`() = runTest {
        val response = ok("hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), requests(FakeConnector { response }).readBounded(response, 100))
        assertTrue(response.closed)
    }

    @Test
    fun `readBounded rejects a declared size over the limit without reading`() = runTest {
        var reads = 0
        val body = object : InputStream() {
            override fun read(): Int {
                reads++
                return -1
            }
        }
        val response = FakeResponse(200, contentLength = 101, body = body)
        val error = runCatching { requests(FakeConnector { response }).readBounded(response, 100) }.exceptionOrNull()
        assertEquals(NetworkProblem.TooLarge, (error as UpdateNetworkException).problem)
        assertEquals(0, reads)
        assertTrue(response.closed)
    }

    @Test
    fun `readBounded rejects an undeclared body over the limit`() = runTest {
        val response = ok(ByteArray(101), declared = -1)
        val error = runCatching { requests(FakeConnector { response }).readBounded(response, 100) }.exceptionOrNull()
        assertEquals(NetworkProblem.TooLarge, (error as UpdateNetworkException).problem)
        assertTrue(response.closed)
    }

    @Test
    fun `copyBounded stops when the coroutine is cancelled`() = runTest {
        val worker = Job()
        var reads = 0
        val body = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (++reads == 2) worker.cancel()
                return 10
            }
        }
        var cancelled = false
        try {
            withContext(worker) { requests(FakeConnector { ok() }).copyBounded(body, 1_000_000) { _, _ -> } }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(2, reads)
    }
}
