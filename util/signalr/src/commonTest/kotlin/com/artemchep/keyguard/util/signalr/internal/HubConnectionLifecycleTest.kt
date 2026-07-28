package com.artemchep.keyguard.util.signalr.internal

import com.artemchep.keyguard.util.signalr.HubConnection
import com.artemchep.keyguard.util.signalr.HubConnectionConfig
import com.artemchep.keyguard.util.signalr.HubConnectionCloseReason
import com.artemchep.keyguard.util.signalr.HubConnectionEvent
import com.artemchep.keyguard.util.signalr.HubConnectionState
import com.artemchep.keyguard.util.signalr.HubMessage
import com.artemchep.keyguard.util.signalr.internal.util.EstablishedConnection
import com.artemchep.keyguard.util.signalr.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val client = testHttpClient()
        val connection = testConnection(client, session)

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
        val client = testHttpClient()
        val connection = testConnection(client, session)

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
    fun `independent cancellation while connecting emits transport closed`() = runTest {
        val session = FakeWebSocketSession(handshakePayload = null)
        val client = testHttpClient()
        val connection = testConnection(client, session)

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )

            session.connected.await()
            session.fail(CancellationException("transport owner cancelled"))

            withTimeout(5.seconds) {
                val disconnecting = events.awaitState(HubConnectionState.DISCONNECTING)
                assertEquals(HubConnectionCloseReason.TransportClosed, disconnecting.reason)
                val disconnected = events.awaitState(HubConnectionState.DISCONNECTED)
                assertEquals(HubConnectionCloseReason.TransportClosed, disconnected.reason)
                session.awaitCloseFrame()
                job.join()
            }
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `cancelling controller before accepting established transport stops it`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val options = testOptions(
            client = client,
            session = session,
            closeTimeout = 50.milliseconds,
        )
        val stopStarted = CompletableDeferred<Unit>()
        val transport = RecordingTransport {
            stopStarted.complete(Unit)
            awaitCancellation()
        }
        val established = CompletableDeferred<Unit>()

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launch {
                runHubConnectionController(
                    scope = this,
                    events = events,
                    options = options,
                    connectConnection = {
                        established.complete(Unit)
                        EstablishedConnection(
                            transport = transport,
                            connectionId = null,
                            initialPayload = null,
                        )
                    },
                )
            }

            events.awaitState(HubConnectionState.CONNECTING)
            established.await()

            withTimeout(5.seconds) {
                job.cancelAndJoin()
            }
            assertEquals(1, transport.stopCalls)
            assertTrue(stopStarted.isCompleted)
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `handshake timeout emits failed disconnected event`() = runTest {
        val session = FakeWebSocketSession(handshakePayload = null)
        val client = testHttpClient()
        val connection = testConnection(
            client = client,
            session = session,
            handshakeResponseTimeout = 50.milliseconds,
        )

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )

            val disconnected = events.awaitState(HubConnectionState.DISCONNECTED)
            val reason = assertIs<HubConnectionCloseReason.Failed>(disconnected.reason)
            assertFalse(reason.cause is CancellationException)
            assertEquals(
                "Server timeout elapsed without receiving a handshake response.",
                reason.cause.message,
            )
            session.awaitCloseFrame()
            job.join()
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `receive failure emits failed disconnected event`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val connection = testConnection(client, session)

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
    fun `transport cancellation emits transport closed and completes`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val connection = testConnection(client, session)

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )
            events.awaitState(HubConnectionState.CONNECTED)

            session.fail(CancellationException("transport owner cancelled"))

            withTimeout(5.seconds) {
                val disconnecting = events.awaitState(HubConnectionState.DISCONNECTING)
                assertEquals(HubConnectionCloseReason.TransportClosed, disconnecting.reason)
                val disconnected = events.awaitState(HubConnectionState.DISCONNECTED)
                assertEquals(HubConnectionCloseReason.TransportClosed, disconnected.reason)
                session.awaitCloseFrame()
                job.join()
            }
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `ignored invocations do not block cancellation`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val connection = testConnection(client, session)

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
        val client = testHttpClient()
        val errors = mutableListOf<String>()
        val logger = Logger { severity, message, cause ->
            if (severity == Logger.Severity.ERROR) {
                errors += "$message: $cause"
            }
        }
        val connection = testConnection(
            client = client,
            session = session,
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
        val client = testHttpClient()
        val connection = testConnection(client, session)

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
        val client = testHttpClient()
        val connection = testConnection(
            client = client,
            session = session,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `keep alive transport cancellation emits transport closed`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val connection = testConnection(
            client = client,
            session = session,
            keepAliveInterval = 10.milliseconds,
        )

        try {
            val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
            val job = launchConnection(
                connection = connection,
                events = events,
            )
            events.awaitState(HubConnectionState.CONNECTED)

            session.failOutgoing(CancellationException("transport owner cancelled"))
            advanceTimeBy(11.milliseconds)

            withTimeout(5.seconds) {
                val disconnecting = events.awaitState(HubConnectionState.DISCONNECTING)
                assertEquals(HubConnectionCloseReason.TransportClosed, disconnecting.reason)
                val disconnected = events.awaitState(HubConnectionState.DISCONNECTED)
                assertEquals(HubConnectionCloseReason.TransportClosed, disconnected.reason)
                job.join()
            }
        } finally {
            close(client, session)
        }
    }

    @Test
    fun `cancelling job does not close injected http client`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val connection = testConnection(client, session)

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
        session: FakeWebSocketSession,
        logger: Logger = Logger.Empty,
        keepAliveInterval: Duration = 1.minutes,
        handshakeResponseTimeout: Duration = 5.seconds,
    ): HubConnection = DefaultHubConnection(
        testOptions(
            client = client,
            session = session,
            logger = logger,
            keepAliveInterval = keepAliveInterval,
            handshakeResponseTimeout = handshakeResponseTimeout,
        ),
    )

    private fun testOptions(
        client: HttpClient,
        session: FakeWebSocketSession,
        logger: Logger = Logger.Empty,
        keepAliveInterval: Duration = 1.minutes,
        handshakeResponseTimeout: Duration = 5.seconds,
        closeTimeout: Duration = 5.seconds,
    ): HubConnectionOptions {
        val config = HubConnectionConfig().apply {
            this.skipNegotiate = true
            this.handshakeResponseTimeout = handshakeResponseTimeout
            this.serverTimeout = 1.minutes
            this.keepAliveInterval = keepAliveInterval
            this.closeTimeout = closeTimeout
            this.logger = logger
        }
        return HubConnectionOptions
            .create(
                url = "https://example.com/hub",
                httpClient = client,
                config = config,
            )
            .copy(
                webSocketSessionConnector = { _, url, _ ->
                    session.connected.complete(url)
                    session
                },
            )
    }

    private fun testHttpClient() = HttpClient(
        MockEngine { request ->
            error("Unexpected HTTP request: ${request.url}")
        },
    ) {
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

private class RecordingTransport(
    private val onStop: suspend () -> Unit = {},
) : Transport {
    var stopCalls: Int = 0
        private set

    override suspend fun send(
        message: ByteArray,
    ) = Unit

    override suspend fun sendText(
        message: String,
    ) = Unit

    override fun receive(): Flow<ByteArray> = kotlinx.coroutines.flow.flow {
        awaitCancellation()
    }

    override suspend fun stop() {
        stopCalls += 1
        onStop()
    }
}
