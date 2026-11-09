package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.feature.p2psync.transport.IceServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IceServersProviderTest {
    private val endpoint = RelayEndpoint("https://relay.example.com", token = "relay-token-0123456789")

    private val response = """
        {
          "iceServers": [
            { "urls": ["stun:turn.example.com:3478"] },
            {
              "urls": ["turn:turn.example.com:3478?transport=udp", "https://not-ice.example.com"],
              "username": "1767225600:0123456789abcdef",
              "credential": "c2VjcmV0"
            },
            { "urls": ["turn:no-credentials.example.com:3478"] },
            { "urls": [] }
          ],
          "ttlSeconds": 3600,
          "unknown": true
        }
    """.trimIndent()

    private class Server(var reply: (Int) -> Reply) {
        var calls = 0
        val authorizations = mutableListOf<String?>()
        val bodies = mutableListOf<String?>()
    }

    private sealed interface Reply {
        class Json(val body: String, val status: HttpStatusCode = HttpStatusCode.OK) : Reply

        data object Unreachable : Reply
    }

    @Test
    fun `servers are parsed and cached for half their ttl`() = runTest {
        val server = Server { Reply.Json(response) }
        var now = 0L
        val provider = IceServersProvider(client(server), endpoint) { now }

        val servers = provider.grant().servers
        assertEquals(
            listOf(
                IceServer(listOf("stun:turn.example.com:3478")),
                IceServer(
                    urls = listOf("turn:turn.example.com:3478?transport=udp"),
                    username = "1767225600:0123456789abcdef",
                    credential = "c2VjcmV0",
                ),
            ),
            servers,
        )
        assertEquals(listOf<String?>("Bearer relay-token-0123456789"), server.authorizations)
        assertEquals(false, servers.toString().contains("c2VjcmV0"))

        // A connection made from the cache still has half an hour before the credential expires.
        now = 1_800L * 1_000L - 1L
        assertEquals(servers, provider.grant().servers)
        assertEquals(1, server.calls)

        now += 1L
        provider.grant()
        assertEquals(2, server.calls)
    }

    @Test
    fun `a grant counts the lifetime of its credentials from when the relay issued them`() = runTest {
        val server = Server { Reply.Json(response) }
        var now = 0L
        val provider = IceServersProvider(client(server), endpoint) { now }
        // 90% of an hour.
        val refreshAfter = 3_240_000L

        now = 1_000L
        val first = provider.grant()
        assertTrue(first.hasTurn)
        assertEquals(refreshAfter, first.msUntilRefresh())

        // From the cache: the same credentials, so less of their lifetime is left.
        now += 1_000_000L
        val cached = provider.grant()
        assertEquals(1, server.calls)
        assertEquals(first.issuedAtMs, cached.issuedAtMs)
        assertEquals(refreshAfter - 1_000_000L, cached.msUntilRefresh())
        assertEquals(refreshAfter - 1_000_000L, first.msUntilRefresh())

        // Fresh credentials once the cache is stale.
        now = 1_000L + 1_800_000L
        assertEquals(refreshAfter, provider.grant().msUntilRefresh())
        assertEquals(2, server.calls)

        // Past their refresh time, it is due at once.
        now += refreshAfter + 5_000L
        assertEquals(0L, first.msUntilRefresh())
    }

    @Test
    fun `a grant without TURN never needs a refresh`() = runTest {
        val server = Server { Reply.Json("""{"iceServers":[{"urls":["stun:a"]}],"ttlSeconds":120}""") }
        val provider = IceServersProvider(client(server), endpoint) { 0L }
        val grant = provider.grant()
        assertEquals(false, grant.hasTurn)
        assertEquals(null, grant.msUntilRefresh())

        val unreachable = IceServersProvider(client(Server { Reply.Unreachable }), endpoint) { 0L }
        assertEquals(null, unreachable.grant().msUntilRefresh())
    }

    @Test
    fun `a short ttl is cached for half of it`() = runTest {
        val server = Server { Reply.Json("""{"iceServers":[{"urls":["stun:a"]}],"ttlSeconds":120}""") }
        var now = 0L
        val provider = IceServersProvider(client(server), endpoint) { now }
        provider.grant()
        now = 59_999L
        provider.grant()
        assertEquals(1, server.calls)
        now = 60_000L
        provider.grant()
        assertEquals(2, server.calls)
    }

    @Test
    fun `failures fall back to host candidates and retry after a minute`() = runTest {
        val replies = mutableListOf<Reply>(
            Reply.Json("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests),
            Reply.Unreachable,
            Reply.Json("""{"iceServers":"nope","ttlSeconds":3600}"""),
            Reply.Json("""{"iceServers":[],"ttlSeconds":0}"""),
            Reply.Json("not json"),
            // Valid, but larger than the 64 KiB a response may take.
            Reply.Json(response + " ".repeat(64 * 1024)),
            Reply.Json(response),
        )
        val server = Server { replies.removeAt(0) }
        var now = 0L
        val provider = IceServersProvider(client(server), endpoint) { now }
        repeat(replies.size - 1) { attempt ->
            assertEquals(emptyList(), provider.grant().servers, "attempt $attempt")
            // Not asked again within the minute.
            now += IceServersProvider.RETRY_AFTER_FAILURE_MS - 1
            assertEquals(emptyList(), provider.grant().servers)
            assertEquals(attempt + 1, server.calls)
            now += 1
        }
        assertTrue(provider.grant().servers.isNotEmpty())
    }

    @Test
    fun `a ticket is sent to the relay's own ice endpoint only`() = runTest {
        val ticket = "00112233445566778899aabbccddeeff"
        val server = Server { Reply.Json("""{"iceServers":[{"urls":["stun:a"]}],"ttlSeconds":60}""") }
        var now = 0L
        val provider = IceServersProvider(client(server), endpoint) { now }
        provider.grant()
        now += 60_000L
        provider.grant(ticket)
        assertEquals(listOf(null, """{"ticket":"$ticket"}"""), server.bodies)

        val elsewhere = RelayEndpoint("https://relay.example.com", iceUrl = "https://ice.example.com/x")
        val other = Server { Reply.Json(response) }
        IceServersProvider(client(other, url = elsewhere.iceServersUrl), elsewhere) { 0L }.grant(ticket)
        assertEquals(listOf<String?>(null), other.bodies)
    }

    @Test
    fun `the access token goes to the relay's own host only`() = runTest {
        val elsewhere = RelayEndpoint(
            relayUrl = "https://relay.example.com",
            iceUrl = "https://ice.example.com/x",
            token = "relay-token-0123456789",
        )
        val other = Server { Reply.Json(response) }
        IceServersProvider(client(other, url = elsewhere.iceServersUrl), elsewhere) { 0L }.grant()
        assertEquals(listOf<String?>(null), other.authorizations)

        val samehost = RelayEndpoint(
            relayUrl = "https://relay.example.com",
            iceUrl = "https://relay.example.com/custom",
            token = "relay-token-0123456789",
        )
        val own = Server { Reply.Json(response) }
        IceServersProvider(client(own, url = samehost.iceServersUrl), samehost) { 0L }.grant()
        assertEquals(listOf<String?>("Bearer relay-token-0123456789"), own.authorizations)
    }

    private fun client(
        server: Server,
        url: String = "https://relay.example.com/v1/ice-servers",
    ) = HttpClient(
        MockEngine { request ->
            server.calls += 1
            server.authorizations += request.headers[HttpHeaders.Authorization]
            server.bodies += (request.body as? TextContent)?.let { body ->
                assertEquals(ContentType.Application.Json, body.contentType)
                body.text
            }
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(url, request.url.toString())
            when (val reply = server.reply(server.calls)) {
                is Reply.Json -> respond(
                    content = reply.body,
                    status = reply.status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )

                Reply.Unreachable -> throw IOException("unreachable")
            }
        },
    )
}
