package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.mailbox.MailboxKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RelayMailboxClientTest {
    private val ticket = "00112233445566778899aabbccddeeff"
    private val item = "ab".repeat(32)
    private val tag = "0123456789abcdef0123456789abcdef"
    private val state = RelayMailboxRef(ticket, MailboxKind.STATE, item)

    private val requests = mutableListOf<HttpRequestData>()

    // The engine answers on its own threads, so the tests run in real time: a virtual
    // clock would reach the request timeout at once.
    private fun client(
        endpoint: RelayEndpoint = RelayEndpoint("wss://relay.example.com/v1/ws", token = "relay-token"),
        requestTimeoutMs: Long = RelayMailboxClient.REQUEST_TIMEOUT_MS,
        reply: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RelayMailboxClient(
        httpClient = HttpClient(
            MockEngine { request ->
                requests += request
                reply(request)
            },
        ),
        endpoint = endpoint,
        requestTimeoutMs = requestTimeoutMs,
    )

    private fun MockRequestHandleScope.status(
        status: HttpStatusCode,
        vararg headers: Pair<String, String>,
    ) = respond(
        content = ByteReadChannel.Empty,
        status = status,
        headers = headersOf(*headers.map { (name, value) -> name to listOf(value) }.toTypedArray()),
    )

    @Test
    fun `a put carries the token, the ticket, the item, the tag and the lifetime`() = runBlocking<Unit> {
        val mailbox = client { status(HttpStatusCode.NoContent, "X-Keyguard-Expires" to "1700000000") }
        val result = mailbox.put(state, tag, ttlSeconds = 3_600, body = byteArrayOf(1, 2, 3))
        assertEquals(RelayMailboxResult.Done(expiresAt = 1_700_000_000), result)
        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        // The item id is a capability: it never goes in the URL.
        assertEquals("https://relay.example.com/v1/mailbox/state", request.url.toString())
        assertEquals("Bearer relay-token", request.headers[HttpHeaders.Authorization])
        assertEquals(ticket, request.headers["X-Keyguard-Ticket"])
        assertEquals(item, request.headers["X-Keyguard-Item"])
        assertEquals(tag, request.headers["X-Keyguard-Tag"])
        assertEquals("3600", request.headers["X-Keyguard-Ttl"])
        val body = assertIs<OutgoingContent.ByteArrayContent>(request.body)
        assertContentEquals(byteArrayOf(1, 2, 3), body.bytes())
        assertEquals(3L, body.contentLength)
    }

    @Test
    fun `a write-once put finds the item there already`() = runBlocking<Unit> {
        val blob = RelayMailboxRef(ticket, MailboxKind.BLOB, item)
        val mailbox = client { request ->
            if (request.headers[HttpHeaders.IfNoneMatch] == "*") {
                respond(
                    content = """{"error":"item_exists"}""",
                    status = HttpStatusCode.PreconditionFailed,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                status(HttpStatusCode.NoContent)
            }
        }
        assertEquals(RelayMailboxResult.Exists, mailbox.put(blob, tag, 60, ByteArray(1), ifAbsent = true))
        assertEquals("*", requests.single().headers[HttpHeaders.IfNoneMatch])
        // A plain put replaces the item, and asks for nothing.
        assertEquals(RelayMailboxResult.Done(), mailbox.put(blob, tag, 60, ByteArray(1)))
        assertNull(requests.last().headers[HttpHeaders.IfNoneMatch])
        // A 412 to any other request breaks the protocol.
        val unexpected = client { status(HttpStatusCode.PreconditionFailed) }
        val badResponse = RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE)
        assertEquals(badResponse, unexpected.put(blob, tag, 60, ByteArray(1)))
        assertEquals(badResponse, unexpected.delete(blob))
    }

    @Test
    fun `a relay without a token gets none, and blobs have their own path`() = runBlocking<Unit> {
        val mailbox = client(RelayEndpoint("http://localhost:8080")) { status(HttpStatusCode.NoContent) }
        mailbox.delete(RelayMailboxRef(ticket, MailboxKind.BLOB, item))
        val request = requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("http://localhost:8080/v1/mailbox/blob", request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
        // A delete is unconditional.
        assertNull(request.headers[HttpHeaders.IfMatch])
    }

    @Test
    fun `a get returns the item, or not modified for the tag it has`() = runBlocking<Unit> {
        val mailbox = client { request ->
            if (request.headers[HttpHeaders.IfNoneMatch] == tag) {
                status(HttpStatusCode.NotModified)
            } else {
                respond(
                    content = byteArrayOf(4, 5, 6),
                    status = HttpStatusCode.OK,
                    headers = headersOf("X-Keyguard-Tag" to listOf(tag), "X-Keyguard-Expires" to listOf("42")),
                )
            }
        }
        val found = assertIs<RelayMailboxResult.Found>(mailbox.get(state, ifNoneMatch = null, maxBytes = 3))
        assertEquals(tag, found.tag)
        assertEquals(42L, found.expiresAt)
        assertContentEquals(byteArrayOf(4, 5, 6), found.body)
        assertEquals(RelayMailboxResult.NotModified, mailbox.get(state, ifNoneMatch = tag, maxBytes = 3))
    }

    @Test
    fun `a head returns the tag only`() = runBlocking<Unit> {
        val mailbox = client { status(HttpStatusCode.OK, "X-Keyguard-Tag" to tag) }
        val found = assertIs<RelayMailboxResult.Found>(mailbox.head(state))
        assertEquals(tag, found.tag)
        assertNull(found.body)
        assertEquals(HttpMethod.Head, requests.single().method)
    }

    @Test
    fun `a body past the limit is never read whole`() = runBlocking<Unit> {
        val declared = client {
            respond(content = ByteArray(10), status = HttpStatusCode.OK, headers = headersOf("X-Keyguard-Tag", tag))
        }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE),
            declared.get(state, ifNoneMatch = null, maxBytes = 9),
        )
        // A body without a length is read up to the limit, and one byte more.
        val streamed = client {
            respond(
                content = ByteReadChannel(ByteArray(1_000_000)),
                status = HttpStatusCode.OK,
                headers = headersOf("X-Keyguard-Tag", tag),
            )
        }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE),
            streamed.get(state, ifNoneMatch = null, maxBytes = 1_000),
        )
        // An item without a tag breaks the protocol.
        val untagged = client { respond(content = ByteArray(1), status = HttpStatusCode.OK) }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE),
            untagged.get(state, ifNoneMatch = null, maxBytes = 10),
        )
    }

    @Test
    fun `every refusal maps to its result`() = runBlocking<Unit> {
        val cases = listOf(
            HttpStatusCode.BadRequest to RelayMailboxResult.Failed(RelayMailboxError.BAD_REQUEST),
            HttpStatusCode.Unauthorized to RelayMailboxResult.Failed(RelayMailboxError.UNAUTHORIZED),
            HttpStatusCode.Forbidden to RelayMailboxResult.Failed(RelayMailboxError.TICKET_REQUIRED),
            HttpStatusCode.NotFound to RelayMailboxResult.NotFound,
            HttpStatusCode.RequestTimeout to RelayMailboxResult.Failed(RelayMailboxError.TIMEOUT),
            HttpStatusCode.PreconditionFailed to RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE),
            HttpStatusCode.PayloadTooLarge to RelayMailboxResult.TooLarge,
            HttpStatusCode.TooManyRequests to RelayMailboxResult.RetryLater(RelayMailboxError.RATE_LIMITED, 7),
            HttpStatusCode.ServiceUnavailable to
                RelayMailboxResult.RetryLater(RelayMailboxError.STORAGE_UNAVAILABLE, 7),
            HttpStatusCode.InsufficientStorage to RelayMailboxResult.RetryLater(RelayMailboxError.STORAGE_FULL, 7),
            HttpStatusCode.InternalServerError to RelayMailboxResult.Failed(RelayMailboxError.BAD_RESPONSE),
        )
        cases.forEach { (code, expected) ->
            val mailbox = client { status(code, HttpHeaders.RetryAfter to "7") }
            assertEquals(expected, mailbox.put(state, tag, ttlSeconds = 60, body = ByteArray(1)), code.toString())
        }
        // A refusal without a time leaves it to the caller.
        val untimed = client { status(HttpStatusCode.InsufficientStorage) }
        assertEquals(
            RelayMailboxResult.RetryLater(RelayMailboxError.STORAGE_FULL, null),
            untimed.put(state, tag, ttlSeconds = 60, body = ByteArray(1)),
        )
    }

    @Test
    fun `an item that did not change is not modified, whatever the app's client caches`() = runBlocking<Unit> {
        val engine = MockEngine { request ->
            requests += request
            respond(content = ByteReadChannel.Empty, status = HttpStatusCode.NotModified)
        }
        // The app's shared client caches responses; a 304 to a request it did not make
        // conditional itself is a broken cache entry to it.
        val shared = HttpClient(engine) { install(HttpCache) }
        val mailbox = KtorKeyguardRelayMailboxes(shared).get(RelayEndpoint("https://relay.example.com"))
        assertEquals(RelayMailboxResult.NotModified, mailbox.get(state, ifNoneMatch = tag, maxBytes = 10))
        assertEquals(tag, requests.single().headers[HttpHeaders.IfNoneMatch])
    }

    @Test
    fun `a network failure, or a request that takes too long, is reported, not thrown`() = runBlocking<Unit> {
        val failing = client { throw IOException("unreachable") }
        assertEquals(RelayMailboxResult.Failed(RelayMailboxError.NETWORK), failing.head(state))
        val stalled = client(requestTimeoutMs = 100L) { awaitCancellation() }
        assertEquals(
            RelayMailboxResult.Failed(RelayMailboxError.NETWORK),
            stalled.get(state, ifNoneMatch = null, maxBytes = 10),
        )
    }
}
