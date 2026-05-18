package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.HubConnection
import com.artemchep.keyguard.util.signalr.hubConnection
import com.artemchep.keyguard.util.signalr.HubConnectionCloseReason
import com.artemchep.keyguard.util.signalr.HubConnectionEvent
import com.artemchep.keyguard.util.signalr.HubConnectionState
import com.artemchep.keyguard.util.signalr.HubMessage
import com.artemchep.keyguard.util.signalr.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.websocket.websocketServerAccept
import io.ktor.util.date.GMTDate
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HubConnectionLifecycleTest {
    @Test
    fun `connection events reach connected state`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient(session)
        val connection = testConnection(client)

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )

            val connected = events.awaitState(HubConnectionState.CONNECTED)

            assertEquals(HubConnectionState.CONNECTED, connected.state)
            assertEquals("wss://example.com/hub", session.connected.await())

            job.cancelAndJoin()
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `cancelling job while connecting completes`() = runTest {
        val session = FakeWebSocketSession(handshakePayload = null)
        val client = testHttpClient(session)
        val connection = testConnection(client)

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )

            session.connected.await()

            withTimeout(5.seconds) {
                job.cancelAndJoin()
            }
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `receive failure emits failed disconnected event`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient(session)
        val connection = testConnection(client)

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )
            events.awaitState(HubConnectionState.CONNECTED)

            session.fail(RuntimeException("network closed"))

            val disconnected = events.awaitState(HubConnectionState.DISCONNECTED)
            val reason = assertIs<HubConnectionCloseReason.Failed>(disconnected.reason)
            assertIs<RuntimeException>(reason.cause)
            assertEquals(HubConnectionState.DISCONNECTED, disconnected.state)
            session.awaitCloseFrame()
            job.join()
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `ignored invocations do not block cancellation`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient(session)
        val connection = testConnection(client)

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )
            events.awaitState(HubConnectionState.CONNECTED)

            repeat(100) { index ->
                session.receiveInvocation("Event$index")
            }

            withTimeout(5.seconds) {
                job.cancelAndJoin()
            }
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `handshake preserves coalesced hub payload`() = runTest {
        val session = FakeWebSocketSession(
            handshakePayload = "{}$RECORD_SEPARATOR{\"type\":1,\"target\":\"Ready\",\"arguments\":[]}$RECORD_SEPARATOR"
                .encodeToByteArray(),
        )
        val client = testHttpClient(session)
        val errors = mutableListOf<String>()
        val logger = Logger { severity, message, cause ->
            if (severity == Logger.Severity.ERROR) {
                errors += "$message: $cause"
            }
        }
        val connection = testConnection(
            client = client,
            logger = logger,
        )

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )

            val result = events.awaitInvocation()

            assertEquals("Ready", result.target)
            job.cancelAndJoin()
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `normal transport close emits disconnected event and completes`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient(session)
        val connection = testConnection(client)

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )
            events.awaitState(HubConnectionState.CONNECTED)

            session.closeIncoming()

            val disconnected = events.awaitState(HubConnectionState.DISCONNECTED)
            assertEquals(HubConnectionCloseReason.TransportClosed, disconnected.reason)
            session.awaitCloseFrame()
            job.join()
        } finally {
            close(client, session)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `json protocol sends hub messages as text frames`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient(session)
        val connection = testConnection(
            client = client,
            keepAliveInterval = 10.milliseconds,
        )

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )
            events.awaitState(HubConnectionState.CONNECTED)

            advanceTimeBy(11.milliseconds)
            session.awaitTextMessage("{\"type\":6}$RECORD_SEPARATOR")

            job.cancelAndJoin()
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `cancelling job does not close injected http client`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient(session)
        val connection = testConnection(client)

        val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
        val job = launchConnection(
            connection = connection,
            events = events,
        )
        events.awaitState(HubConnectionState.CONNECTED)

        job.cancelAndJoin()

        assertTrue(client.coroutineContext[Job]?.isActive == true)
        close(client, session)
    }

    private fun CoroutineScope.launchConnection(
        connection: HubConnection,
        events: SendChannel<HubConnectionEvent>,
    ): Job = launch {
        connection.events()
            .collect { event ->
                events.send(event)
            }
    }

    private suspend fun ReceiveChannel<HubConnectionEvent>.awaitState(
        state: HubConnectionState,
    ): HubConnectionEvent.StateChanged {
        while (true) {
            when (val event = receive()) {
                is HubConnectionEvent.StateChanged -> {
                    if (event.state == state) {
                        return event
                    }
                    if (event.state == HubConnectionState.DISCONNECTED) {
                        error("Disconnected while waiting for $state: ${event.reason}")
                    }
                }

                is HubConnectionEvent.InvocationReceived -> Unit
            }
        }
    }

    private suspend fun ReceiveChannel<HubConnectionEvent>.awaitInvocation(): HubMessage.Invocation {
        while (true) {
            when (val event = receive()) {
                is HubConnectionEvent.InvocationReceived -> return event.invocation
                is HubConnectionEvent.StateChanged -> Unit
            }
        }
    }

    private fun testConnection(
        client: HttpClient,
        logger: Logger = Logger.Empty,
        keepAliveInterval: Duration = 1.minutes,
    ) = hubConnection(
        url = "https://example.com/hub",
        httpClient = client,
    ) {
        this.skipNegotiate = true
        this.handshakeResponseTimeout = 5.seconds
        this.serverTimeout = 1.minutes
        this.keepAliveInterval = keepAliveInterval
        this.logger = logger
    }

    private fun testHttpClient(
        session: FakeWebSocketSession,
    ) = HttpClient(
        MockEngine { request ->
            session.connected.complete(request.url.toString())
            respondWebSocket(
                request = request,
                session = session,
            )
        },
    ) {
        install(WebSockets)
        install(HttpTimeout)
    }

    private fun close(
        client: HttpClient,
        session: FakeWebSocketSession,
    ) {
        client.close()
        session.dispose()
    }
}

private class FakeWebSocketSession(
    handshakePayload: ByteArray? = "{}$RECORD_SEPARATOR".encodeToByteArray(),
) : WebSocketSession {
    private val job = Job()
    private val incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    private val outgoingFrames = Channel<Frame>(Channel.UNLIMITED)

    override val coroutineContext: CoroutineContext = job
    override var masking: Boolean = false
    override var maxFrameSize: Long = Long.MAX_VALUE
    override val incoming: ReceiveChannel<Frame> = incomingFrames
    override val outgoing: SendChannel<Frame> = outgoingFrames
    override val extensions: List<WebSocketExtension<*>> = emptyList()

    val connected = kotlinx.coroutines.CompletableDeferred<String>()

    init {
        handshakePayload?.let { payload ->
            incomingFrames.trySend(Frame.Text(payload.decodeToString()))
        }
    }

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

    suspend fun awaitCloseFrame() {
        withTimeout(5.seconds) {
            while (true) {
                val frame = outgoingFrames.receive()
                if (frame is Frame.Close) {
                    return@withTimeout
                }
            }
        }
    }

    suspend fun awaitTextMessage(
        expected: String,
    ) {
        withTimeout(5.seconds) {
            while (true) {
                when (val frame = outgoingFrames.receive()) {
                    is Frame.Text -> {
                        if (frame.readText() == expected) {
                            return@withTimeout
                        }
                    }

                    is Frame.Binary -> error("Unexpected binary websocket frame: ${frame.readBytes().size} bytes.")
                    else -> Unit
                }
            }
        }
    }

    fun closeIncoming() {
        incomingFrames.close()
    }

    fun fail(
        cause: Throwable,
    ) {
        incomingFrames.close(cause)
    }

    fun receiveInvocation(
        target: String,
    ) {
        incomingFrames.trySend(
            Frame.Text(
                "{\"type\":1,\"target\":\"$target\",\"arguments\":[]}$RECORD_SEPARATOR",
            ),
        )
    }

    fun dispose() {
        incomingFrames.close()
        outgoingFrames.close()
        job.cancel()
    }
}

@OptIn(InternalAPI::class)
private fun MockRequestHandleScope.respondWebSocket(
    request: HttpRequestData,
    session: FakeWebSocketSession,
): HttpResponseData {
    val acceptHeaders = request.headers[HttpHeaders.SecWebSocketKey]
        ?.let { key ->
            headersOf(HttpHeaders.SecWebSocketAccept, websocketServerAccept(key))
        }
        ?: Headers.Empty
    return HttpResponseData(
        statusCode = HttpStatusCode.SwitchingProtocols,
        requestTime = GMTDate(),
        headers = acceptHeaders,
        version = HttpProtocolVersion.HTTP_1_1,
        body = session,
        callContext = request.executionContext,
    )
}
