package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * A GET with a [RelayMailboxDownload]: a body cut short leaves its bytes, and the next GET
 * asks only for the rest with `Range` and `If-Range`, see [RelayMailbox.get].
 */
class RelayMailboxResumeTest {
    private val ticket = "00112233445566778899aabbccddeeff"
    private val tag = "0123456789abcdef0123456789abcdef"
    private val blob = RelayMailboxRef(ticket, MailboxKind.BLOB, "ab".repeat(32))
    private val etag = "\"18f2a3b4c5-a\""
    private val content = ByteArray(10) { (it + 1).toByte() }

    private val requests = mutableListOf<HttpRequestData>()

    // The engine answers on its own threads, so the tests run in real time.
    private fun client(
        reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RelayMailboxClient(
        httpClient = HttpClient(
            MockEngine { request ->
                requests += request
                reply(request)
            },
        ),
        endpoint = RelayEndpoint("https://relay.example.com"),
    )

    private fun headers(vararg headers: Pair<String, String>): Headers =
        headersOf(*headers.map { (name, value) -> name to listOf(value) }.toTypedArray())

    /** A download that holds the first [size] bytes of the [content] with the [etag]. */
    private fun downloadOf(
        size: Int,
        etag: String? = this.etag,
        total: Long? = content.size.toLong(),
    ) = RelayMailboxDownload(blob.item).apply {
        this.etag = etag
        this.total = total
        append(content, size)
    }

    /** A 206 with the [content] from [from], which names the [range]. */
    private fun MockRequestHandleScope.partial(
        range: String,
        from: Int = 4,
        etag: String = this@RelayMailboxResumeTest.etag,
    ) = respond(
        content = content.copyOfRange(from, content.size),
        status = HttpStatusCode.PartialContent,
        headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ETag to etag, HttpHeaders.ContentRange to range),
    )

    @Test
    fun `a download cut short keeps its bytes, and the next get asks for the rest only`() = runBlocking<Unit> {
        val body = ByteChannel(autoFlush = true)
        val download = RelayMailboxDownload(blob.item)
        val slow = client {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headers(
                    "X-Keyguard-Tag" to tag,
                    HttpHeaders.ETag to etag,
                    HttpHeaders.ContentLength to "10",
                ),
            )
        }
        // The link stalls 4 bytes into the item, and the sync window ends.
        val call = launch { slow.get(blob, ifNoneMatch = null, maxBytes = 10, download = download) }
        body.writeFully(content, 0, 4)
        withTimeout(5.seconds) {
            while (download.size < 4L) delay(10L)
        }
        call.cancelAndJoin()
        assertEquals(4L, download.size)
        assertEquals(etag, download.etag)
        assertEquals(10L, download.total)
        assertNull(requests.single().headers[HttpHeaders.Range])

        val found = client { partial("bytes 4-9/10") }.get(blob, ifNoneMatch = null, maxBytes = 10, download = download)
        assertContentEquals(content, assertIs<RelayMailboxResult.Found>(found).body)
        assertEquals(tag, found.tag)
        assertEquals("bytes=4-", requests.last().headers[HttpHeaders.Range])
        assertEquals(etag, requests.last().headers[HttpHeaders.IfRange])
        // The download handed the item over.
        assertEquals(0L, download.size)
        assertNull(download.etag)
    }

    @Test
    fun `a body that ends early keeps its bytes, and one past the item drops them`() = runBlocking<Unit> {
        val short = downloadOf(size = 0, etag = null, total = null)
        val ended = client {
            respond(
                content = ByteReadChannel(content.copyOf(4)),
                status = HttpStatusCode.OK,
                headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ETag to etag, HttpHeaders.ContentLength to "10"),
            )
        }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.NETWORK),
            ended.get(blob, ifNoneMatch = null, maxBytes = 10, download = short),
        )
        assertEquals(4L, short.size)
        assertEquals(etag, short.etag)

        // More bytes than the item has: the response is broken.
        val long = downloadOf(size = 0, etag = null, total = null)
        val overlong = client {
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ETag to etag, HttpHeaders.ContentLength to "4"),
            )
        }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE),
            overlong.get(blob, ifNoneMatch = null, maxBytes = 10, download = long),
        )
        assertEquals(0L, long.size)
        // Without a length, never more than the limit.
        val unbounded = client {
            respond(content = content, status = HttpStatusCode.OK, headers = headers("X-Keyguard-Tag" to tag))
        }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE),
            unbounded.get(blob, ifNoneMatch = null, maxBytes = 9, download = long),
        )
        assertEquals(0L, long.size)
    }

    @Test
    fun `a 200 to a resumed get starts over with the new upload`() = runBlocking<Unit> {
        val download = downloadOf(size = 4)
        val upload = ByteArray(8) { 42 }
        val replaced = client {
            respond(
                content = upload,
                status = HttpStatusCode.OK,
                headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ETag to "\"18f2a3b4d0-8\""),
            )
        }
        val found = replaced.get(blob, ifNoneMatch = null, maxBytes = 10, download = download)
        assertContentEquals(upload, assertIs<RelayMailboxResult.Found>(found).body)
        assertEquals("bytes=4-", requests.single().headers[HttpHeaders.Range])
        assertEquals(etag, requests.single().headers[HttpHeaders.IfRange])
        assertEquals(0L, download.size)
    }

    @Test
    fun `a 206 that does not fit the bytes held drops them`() = runBlocking<Unit> {
        val cases: List<Pair<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>> = listOf(
            "another start" to { partial("bytes 3-9/10", from = 3) },
            "another length" to { partial("bytes 4-10/11") },
            "not to the end" to { partial("bytes 4-8/10") },
            "a signed length" to { partial("bytes 4-9/+10") },
            "another upload" to { partial("bytes 4-9/10", etag = "\"18f2a3b4d0-a\"") },
            "no range" to {
                respond(
                    content = content.copyOfRange(4, 10),
                    status = HttpStatusCode.PartialContent,
                    headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ETag to etag),
                )
            },
            "no ETag" to {
                respond(
                    content = content.copyOfRange(4, 10),
                    status = HttpStatusCode.PartialContent,
                    headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ContentRange to "bytes 4-9/10"),
                )
            },
        )
        cases.forEach { (name, reply) ->
            val download = downloadOf(size = 4)
            val result = client(reply).get(blob, ifNoneMatch = null, maxBytes = 10, download = download)
            assertEquals(RelayMailboxResult.Failed(RelayMailboxError.NETWORK), result, name)
            assertEquals(0L, download.size, name)
            assertNull(download.etag, name)
        }
        // An item larger than the limit, whose length the bytes held did not name.
        val unknown = downloadOf(size = 4, total = null)
        val large = client { partial("bytes 4-19/20") }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.NETWORK),
            large.get(blob, ifNoneMatch = null, maxBytes = 10, download = unknown),
        )
        assertEquals(0L, unknown.size)
    }

    @Test
    fun `a range past the end of the item drops the bytes`() = runBlocking<Unit> {
        val mailbox = client {
            respond(
                content = ByteReadChannel.Empty,
                status = HttpStatusCode.RequestedRangeNotSatisfiable,
                headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ContentRange to "bytes */10"),
            )
        }
        val download = downloadOf(size = 4)
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.NETWORK),
            mailbox.get(blob, ifNoneMatch = null, maxBytes = 10, download = download),
        )
        assertEquals(0L, download.size)
        assertNull(download.etag)
        // A get that asked for no range never gets one.
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE),
            mailbox.get(blob, ifNoneMatch = null, maxBytes = 10),
        )
    }

    @Test
    fun `bytes without an ETag never outlive their request`() = runBlocking<Unit> {
        // From an older relay: no ETag, so the bytes are of no known upload.
        val body = ByteChannel(autoFlush = true)
        val download = RelayMailboxDownload(blob.item)
        val old = client {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ContentLength to "10"),
            )
        }
        val call = launch { old.get(blob, ifNoneMatch = null, maxBytes = 10, download = download) }
        body.writeFully(content, 0, 4)
        withTimeout(5.seconds) {
            while (download.size < 4L) delay(10L)
        }
        call.cancelAndJoin()
        assertEquals(0L, download.size)

        val ended = client {
            respond(
                content = ByteReadChannel(content.copyOf(4)),
                status = HttpStatusCode.OK,
                headers = headers("X-Keyguard-Tag" to tag, HttpHeaders.ContentLength to "10"),
            )
        }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.NETWORK),
            ended.get(blob, ifNoneMatch = null, maxBytes = 10, download = download),
        )
        assertEquals(0L, download.size)
        // Bytes held without an ETag ask for no range.
        val whole = client {
            respond(content = content, status = HttpStatusCode.OK, headers = headers("X-Keyguard-Tag" to tag))
        }
        val found = whole.get(blob, ifNoneMatch = null, maxBytes = 10, download = downloadOf(size = 4, etag = null))
        assertContentEquals(content, assertIs<RelayMailboxResult.Found>(found).body)
        assertNull(requests.last().headers[HttpHeaders.Range])
        assertNull(requests.last().headers[HttpHeaders.IfRange])
    }
}
