package com.artemchep.keyguard.util.signalr

import com.artemchep.keyguard.util.signalr.internal.HubConnectionOptions
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class HubConnectionConfigTest {
    @Test
    fun `connection rejects nonpositive keep alive intervals before network activity`() {
        val engine = MockEngine { request ->
            error("Unexpected HTTP request: ${request.url}")
        }
        val client = HttpClient(engine)

        try {
            val intervals = listOf(
                Duration.ZERO,
                (-1).nanoseconds,
                (-1).seconds,
                -Duration.INFINITE,
            )
            intervals.forEach { interval ->
                val exception = assertFailsWith<IllegalArgumentException>("Interval: $interval") {
                    hubConnection(
                        url = "https://example.com/hub",
                        httpClient = client,
                    ) {
                        keepAliveInterval = interval
                    }
                }
                assertEquals("keepAliveInterval must be greater than zero.", exception.message)
            }
            assertTrue(engine.requestHistory.isEmpty())
            assertTrue(client.coroutineContext[Job]?.isActive == true)
        } finally {
            client.close()
        }
    }

    @Test
    fun `connection options preserve positive keep alive intervals`() {
        val client = HttpClient(MockEngine { request ->
            error("Unexpected HTTP request: ${request.url}")
        })

        try {
            val intervals = listOf(
                1.nanoseconds,
                10.milliseconds,
                15.seconds,
                Duration.INFINITE,
            )
            intervals.forEach { interval ->
                val options = HubConnectionOptions.create(
                    url = "https://example.com/hub",
                    httpClient = client,
                    config = HubConnectionConfig().apply {
                        keepAliveInterval = interval
                    },
                )
                assertEquals(interval, options.keepAliveInterval)
            }
        } finally {
            client.close()
        }
    }

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
