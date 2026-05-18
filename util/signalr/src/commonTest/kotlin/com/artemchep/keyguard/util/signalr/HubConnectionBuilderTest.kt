package com.artemchep.keyguard.util.signalr

import com.artemchep.keyguard.util.signalr.internal.protocols.MessagePackHubProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HubConnectionConfigTest {
    @Test
    fun `connection uses injected http client for negotiate requests`() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = """{"negotiateVersion":1,"availableTransports":[]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine)

        try {
            val result = async {
                runCatching {
                    hubConnection(
                        url = "https://example.com/hub",
                        httpClient = client,
                    ) {
                        protocol = MessagePackHubProtocol()
                        skipNegotiate = false
                    }
                        .events()
                        .toList()
                }
            }

            val outcome = result.await()
            assertTrue(outcome.isSuccess)
            val events = outcome.getOrThrow()
            val disconnected = events
                .filterIsInstance<HubConnectionEvent.StateChanged>()
                .last()
            assertEquals(HubConnectionState.DISCONNECTED, disconnected.state)
            assertIs<HubConnectionCloseReason.Failed>(disconnected.reason)

            val request = engine.requestHistory.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                "https://example.com/hub/negotiate?negotiateVersion=1",
                request.url.toString(),
            )
            assertTrue(client.coroutineContext[Job]?.isActive == true)
        } finally {
            client.close()
        }
    }

}
