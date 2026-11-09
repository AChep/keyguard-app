package com.artemchep.keyguard.provider.keyguard.transport

import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.provider.keyguard.session.eventually
import com.artemchep.keyguard.provider.keyguard.session.realTime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class KtorKeyguardRelayConnectorTest {
    @Test
    fun `the rooms of one relay share a WebSocket`() = realTime {
        // Every upgrade hangs, so each WebSocket asks exactly once.
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val httpClient = HttpClient(
            MockEngine { request ->
                requests += "${request.url} ${request.headers[HttpHeaders.Authorization]}"
                awaitCancellation()
            },
        )
        val crypto = NativeCryptoGenerator()
        val connector = KtorKeyguardRelayConnector(httpClient)
        val first = RelayRoom.forInvite(ByteArray(32) { 1 }, crypto)
        val second = RelayRoom.forInvite(ByteArray(32) { 2 }, crypto)
        val relay = RelayEndpoint("https://relay.example.com/")
        val sameRelay = RelayEndpoint("wss://relay.example.com/v1/ws")
        val withToken = RelayEndpoint("https://relay.example.com", token = "token")
        val other = RelayEndpoint("https://other.example.com")

        val jobs = listOf(
            connector.open(relay, first),
            connector.open(sameRelay, second),
            connector.open(withToken, first),
            connector.open(other, first),
        ).map { connection -> launch { connection.run() } }
        eventually { requests.size >= 3 }
        delay(SETTLE_MS)
        assertEquals(
            listOf(
                "wss://other.example.com/v1/ws null",
                "wss://relay.example.com/v1/ws Bearer token",
                "wss://relay.example.com/v1/ws null",
            ),
            requests.sorted(),
        )
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `a refused upgrade is a relay error`() = runTest {
        val endpoint = RelayEndpoint("https://relay.example.com")
        mapOf(
            HttpStatusCode.Unauthorized to RelayError.Unauthorized,
            HttpStatusCode.TooManyRequests to RelayError.TooManyConnections,
            HttpStatusCode.ServiceUnavailable to RelayError.Http(503),
        ).forEach { (status, error) ->
            val httpClient = HttpClient(MockEngine { respond("", status) })
            val dialer = KtorRelayDialer(httpClient, endpoint)
            assertEquals(error, dialer.dial { fail("The relay refused the upgrade") })
        }
    }

    private companion object {
        /** Long enough for a stray second WebSocket to ask. */
        const val SETTLE_MS = 300L
    }
}
