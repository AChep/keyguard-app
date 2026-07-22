package com.artemchep.keyguard.util.signalr.internal.util

import com.artemchep.keyguard.util.signalr.HubConnectionHttpException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class KtorTransportTest {
    @Test
    fun `uppercase HTTP schemes are converted to websocket schemes`() = runTest {
        assertWebSocketUrl(
            url = "HTTPS://example.com/hub",
            expectedUrl = "wss://example.com/hub",
        )
        assertWebSocketUrl(
            url = "HTTP://example.com/hub",
            expectedUrl = "ws://example.com/hub",
        )
    }

    @Test
    fun `HTTP prefix without scheme delimiter is not converted`() = runTest {
        assertWebSocketUrl(
            url = "https-example.com/hub",
            expectedUrl = "https-example.com/hub",
        )
    }

    @Test
    fun `websocket upgrade opens transport`() = runTest {
        val session = FakeWebSocketSession()
        val client = HttpClient(
            MockEngine {
                HttpResponseData(
                    statusCode = HttpStatusCode.SwitchingProtocols,
                    requestTime = GMTDate(),
                    headers = headersOf(),
                    version = HttpProtocolVersion.HTTP_1_1,
                    body = session,
                    callContext = currentCoroutineContext(),
                )
            },
        ) {
            install(WebSockets)
        }

        try {
            val transport = client.connectTransport(
                url = "https://example.com/hub",
                headers = emptyMap(),
            )

            withTimeout(5.seconds) {
                transport.stop()
            }
        } finally {
            client.close()
            session.dispose()
        }
    }

    @Test
    fun `websocket upgrade failure preserves HTTP status`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "Unauthorized",
                    status = HttpStatusCode.Unauthorized,
                )
            },
        ) {
            expectSuccess = true
            install(WebSockets)
        }

        try {
            val exception = assertFailsWith<HubConnectionHttpException> {
                client.connectTransport(
                    url = "https://example.com/hub",
                    headers = emptyMap(),
                )
            }

            assertEquals(HttpStatusCode.Unauthorized, exception.statusCode)
        } finally {
            client.close()
        }
    }
}

private suspend fun assertWebSocketUrl(
    url: String,
    expectedUrl: String,
) {
    val session = FakeWebSocketSession()
    val client = HttpClient(
        MockEngine { error("Unexpected HTTP request.") },
    )

    try {
        var connectedUrl: String? = null
        val transport = client.connectTransport(
            url = url,
            headers = emptyMap(),
            webSocketSessionConnector = { _, actualUrl, _ ->
                connectedUrl = actualUrl
                session
            },
        )

        assertEquals(expectedUrl, connectedUrl)
        withTimeout(5.seconds) {
            transport.stop()
        }
    } finally {
        client.close()
        session.dispose()
    }
}

private class FakeWebSocketSession : WebSocketSession {
    private val job = Job()
    private val incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    private val outgoingFrames = Channel<Frame>(Channel.UNLIMITED)

    override val coroutineContext: CoroutineContext = job
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override val incoming: ReceiveChannel<Frame> = incomingFrames
    override val outgoing: SendChannel<Frame> = outgoingFrames
    override val extensions: List<WebSocketExtension<*>> = emptyList()

    override suspend fun flush() = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    @Deprecated(
        "Use cancel() instead.",
        ReplaceWith("cancel()", "kotlinx.coroutines.cancel"),
        level = DeprecationLevel.ERROR,
    )
    override fun terminate() {
        dispose()
    }

    fun dispose() {
        incomingFrames.close()
        outgoingFrames.close()
        job.cancel()
    }
}
