package com.dirtyredz.gvmled.data.remote

import com.dirtyredz.gvmled.update.NetworkProblem
import com.dirtyredz.gvmled.update.UpdateNetworkException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One HTTP response with its body still open. [close] releases the connection. */
interface RawResponse : AutoCloseable {
    val code: Int
    fun header(name: String): String?
    val contentLength: Long
    val body: InputStream
}

/** Opens one request without following redirects. The platform version is [UrlConnectionConnector]. */
fun interface HttpConnector {
    fun open(url: URL, headers: Map<String, String>): RawResponse
}

/**
 * Bounded HTTPS GETs shared by release metadata and APK downloads: HTTPS only, the first URL's host
 * must be in [requestHosts], each redirect must stay HTTPS and land on [redirectHosts], at most
 * [maxRedirects] hops, and bodies are read to a hard byte limit. Failures surface as
 * [UpdateNetworkException] with the reason the Settings screen explains (offline, timeout, rate
 * limited, not found, server).
 */
class HttpsRequests(
    private val connector: HttpConnector,
    private val requestHosts: Set<String>,
    private val redirectHosts: Set<String>,
    private val maxRedirects: Int = 4,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    /** Follows allowed redirects to a 2xx response; the caller must close it. */
    fun get(url: String, headers: Map<String, String>): RawResponse {
        var current = parse(url).also { require(it, requestHosts) }
        repeat(maxRedirects + 1) {
            val response = mapping { connector.open(current, headers) }
            when (response.code) {
                in 200..299 -> return response
                301, 302, 303, 307, 308 -> {
                    val location = response.header("Location")
                    response.close()
                    location ?: refuse("Redirect without a location")
                    current = parse(URI(current.toString()).resolve(location).toString()).also { require(it, redirectHosts) }
                }
                else -> response.use { throw failureFor(it) }
            }
        }
        refuse("Too many redirects")
    }

    /** The complete body, failing as soon as it would exceed [maxBytes]. */
    suspend fun readBounded(response: RawResponse, maxBytes: Long): ByteArray = response.use {
        if (it.contentLength > maxBytes) throw UpdateNetworkException(NetworkProblem.TooLarge, "Response too large")
        val out = ByteArrayOutputStream()
        copyBounded(it.body, maxBytes) { buffer, count -> out.write(buffer, 0, count) }
        out.toByteArray()
    }

    /** Streams the body to [sink] in chunks; checks cancellation between chunks. Returns the byte count. */
    suspend fun copyBounded(input: InputStream, maxBytes: Long, sink: (ByteArray, Int) -> Unit): Long {
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = mapping { input.read(buffer) }
            if (read < 0) return total
            total += read
            if (total > maxBytes) throw UpdateNetworkException(NetworkProblem.TooLarge, "Response exceeded $maxBytes bytes")
            sink(buffer, read)
        }
    }

    private fun failureFor(response: RawResponse): UpdateNetworkException {
        val code = response.code
        val remaining = response.header("x-ratelimit-remaining")
        val retryAfter = response.header("retry-after")?.toLongOrNull()
        val reset = response.header("x-ratelimit-reset")?.toLongOrNull()
        return when {
            code == 429 || (code == 403 && (remaining == "0" || retryAfter != null)) -> UpdateNetworkException(
                NetworkProblem.RateLimited,
                "GitHub rate limit",
                retryAtMillis = retryAfter?.let { wallMillis() + it * 1_000 } ?: reset?.let { it * 1_000 },
            )
            code == 404 -> UpdateNetworkException(NetworkProblem.NoRelease, "Not found")
            code >= 500 -> UpdateNetworkException(NetworkProblem.Server, "GitHub server error $code")
            else -> UpdateNetworkException(NetworkProblem.Refused, "Unexpected HTTP $code")
        }
    }

    private fun parse(url: String): URL = try {
        URI(url).toURL()
    } catch (_: Exception) {
        refuse("Malformed URL")
    }

    private fun require(url: URL, hosts: Set<String>) {
        if (url.protocol != "https") refuse("Only HTTPS is allowed")
        if (url.host !in hosts) refuse("Host ${url.host} is not allowed")
        if (url.userInfo != null || (url.port != -1 && url.port != 443)) refuse("Unexpected URL authority")
    }

    private fun refuse(message: String): Nothing = throw UpdateNetworkException(NetworkProblem.Refused, message)

    companion object {
        private const val CHUNK = 64 * 1024

        /** Maps platform I/O failures onto the reasons shown to the owner. */
        inline fun <T> mapping(block: () -> T): T = try {
            block()
        } catch (failure: UpdateNetworkException) {
            throw failure
        } catch (failure: SocketTimeoutException) {
            throw UpdateNetworkException(NetworkProblem.Timeout, failure.message ?: "Timed out")
        } catch (failure: UnknownHostException) {
            throw UpdateNetworkException(NetworkProblem.Offline, failure.message ?: "No network")
        } catch (failure: ConnectException) {
            throw UpdateNetworkException(NetworkProblem.Offline, failure.message ?: "No network")
        } catch (failure: NoRouteToHostException) {
            throw UpdateNetworkException(NetworkProblem.Offline, failure.message ?: "No network")
        } catch (failure: SSLException) {
            throw UpdateNetworkException(NetworkProblem.Refused, failure.message ?: "Secure connection failed")
        } catch (failure: IOException) {
            throw UpdateNetworkException(NetworkProblem.Offline, failure.message ?: "Network error")
        }
    }
}

/** [HttpConnector] over the platform's `HttpURLConnection`: no caching, no automatic redirects. */
class UrlConnectionConnector(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : HttpConnector {
    override fun open(url: URL, headers: Map<String, String>): RawResponse {
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        headers.forEach(connection::setRequestProperty)
        val code = try {
            connection.responseCode
        } catch (failure: IOException) {
            connection.disconnect()
            throw failure
        }
        return object : RawResponse {
            override val code = code
            override fun header(name: String): String? = connection.getHeaderField(name)
            override val contentLength = connection.contentLengthLong
            override val body: InputStream by lazy {
                if (code in 200..299) connection.inputStream else connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            }
            override fun close() = connection.disconnect()
        }
    }
}
