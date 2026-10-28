package com.dirtyredz.gvmled.data.remote

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL

/** A scripted [RawResponse]; header names match case-insensitively and [closed] tracks close(). */
internal class FakeResponse(
    override val code: Int,
    private val headers: Map<String, String> = emptyMap(),
    override val contentLength: Long = -1,
    override val body: InputStream = ByteArrayInputStream(ByteArray(0)),
) : RawResponse {
    var closed = false
        private set

    override fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun close() {
        closed = true
    }
}

/** Records every opened URL and its request headers; [script] returns a [RawResponse] or throws. */
internal class FakeConnector(private val script: (URL) -> RawResponse) : HttpConnector {
    val opened = mutableListOf<String>()
    val requestHeaders = mutableListOf<Map<String, String>>()

    override fun open(url: URL, headers: Map<String, String>): RawResponse {
        opened += url.toString()
        requestHeaders += headers
        return script(url)
    }

    companion object {
        /** Plays [steps] in order, one per request. */
        fun sequence(vararg steps: () -> RawResponse): FakeConnector {
            var index = 0
            return FakeConnector { steps[index++]() }
        }
    }
}
