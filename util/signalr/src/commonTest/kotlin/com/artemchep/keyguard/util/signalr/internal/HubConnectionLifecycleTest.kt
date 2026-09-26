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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

class HubConnectionCleanupTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling while disconnecting event is blocked completes transport cleanup`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val closeRequested = CompletableDeferred<Unit>()
        val disconnectingStarted = CompletableDeferred<Unit>()
        var stopContextWasActive = false
        var closed = false
        val recordingTransport = RecordingTransport {
            stopContextWasActive = currentCoroutineContext().isActive
            delay(1.milliseconds)
            closed = true
        }
        val transport = object : Transport by recordingTransport {
            override fun receive(): Flow<ByteArray> = flow {
                closeRequested.await()
            }
        }
        val events = Channel<HubConnectionEvent>(capacity = 1)
        val observedEvents = object : SendChannel<HubConnectionEvent> by events {
            override suspend fun send(element: HubConnectionEvent) {
                if (element is HubConnectionEvent.StateChanged &&
                    element.state == HubConnectionState.DISCONNECTING
                ) {
                    disconnectingStarted.complete(Unit)
                }
                events.send(element)
            }
        }
        val job = launch {
            runHubConnectionController(
                scope = this,
                events = observedEvents,
                options = testOptions(client, session, closeTimeout = 50.milliseconds),
                connectConnection = {
                    EstablishedConnection(
                        transport = transport,
                        connectionId = "test-connection",
                        initialPayload = null,
                    )
                },
            )
        }

        try {
            events.awaitState(HubConnectionState.CONNECTING)
            // Leave CONNECTED queued so DISCONNECTING cannot be delivered.
            runCurrent()
            closeRequested.complete(Unit)
            disconnectingStarted.await()
            runCurrent()
            assertEquals(0, recordingTransport.stopCalls)
            assertFalse(job.isCompleted)

            withTimeout(5.seconds) {
                job.cancelAndJoin()
            }

            assertEquals(1, recordingTransport.stopCalls)
            assertTrue(
                closed,
                "Transport must finish suspending cleanup; stopContextWasActive=$stopContextWasActive",
            )
            assertTrue(stopContextWasActive)
            assertTrue(job.isCancelled)
        } finally {
            job.cancelAndJoin()
            events.cancel()
            close(client, session)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling during transport cleanup lets it finish`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val closeRequested = CompletableDeferred<Unit>()
        val stopStarted = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        var closed = false
        val recordingTransport = RecordingTransport {
            stopStarted.complete(Unit)
            releaseStop.await()
            closed = true
        }
        val transport = object : Transport by recordingTransport {
            override fun receive(): Flow<ByteArray> = flow {
                closeRequested.await()
            }
        }
        val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
        val job = launch {
            runHubConnectionController(
                scope = this,
                events = events,
                options = testOptions(client, session, closeTimeout = 50.milliseconds),
                connectConnection = {
                    EstablishedConnection(transport, "test-connection", null)
                },
            )
        }

        try {
            events.awaitState(HubConnectionState.CONNECTED)
            closeRequested.complete(Unit)
            events.awaitState(HubConnectionState.DISCONNECTING)
            stopStarted.await()

            job.cancel()
            runCurrent()
            assertFalse(job.isCompleted, "Cancellation must wait for transport cleanup")
            assertFalse(closed)

            releaseStop.complete(Unit)
            withTimeout(5.seconds) {
                job.join()
            }
            assertTrue(closed)
            assertEquals(1, recordingTransport.stopCalls)
            assertTrue(job.isCancelled)
        } finally {
            releaseStop.complete(Unit)
            job.cancelAndJoin()
            events.cancel()
            close(client, session)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancellation during stalled transport cleanup respects close timeout`() = runTest {
        val session = FakeWebSocketSession()
        val client = testHttpClient()
        val closeRequested = CompletableDeferred<Unit>()
        val stopStarted = CompletableDeferred<Unit>()
        val stopFinished = CompletableDeferred<Unit>()
        val receiveFinished = CompletableDeferred<Unit>()
        val recordingTransport = RecordingTransport {
            stopStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopFinished.complete(Unit)
            }
        }
        val transport = object : Transport by recordingTransport {
            override fun receive(): Flow<ByteArray> = flow {
                try {
                    closeRequested.await()
                    emit("{\"type\":7}$RECORD_SEPARATOR".encodeToByteArray())
                    awaitCancellation()
                } finally {
                    receiveFinished.complete(Unit)
                }
            }
        }
        val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
        val job = launch {
            runHubConnectionController(
                scope = this,
                events = events,
                options = testOptions(client, session, closeTimeout = 50.milliseconds),
                connectConnection = {
                    EstablishedConnection(transport, "test-connection", null)
                },
            )
        }

        try {
            events.awaitState(HubConnectionState.CONNECTED)
            closeRequested.complete(Unit)
            events.awaitState(HubConnectionState.DISCONNECTING)
            stopStarted.await()
            job.cancel()
            runCurrent()

            assertTrue(receiveFinished.isCompleted, "The session receive job must be cancelled")
            advanceTimeBy(49.milliseconds)
            runCurrent()
            assertFalse(stopFinished.isCompleted, "Caller cancellation must not bypass the close timeout")
            assertFalse(job.isCompleted)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertTrue(stopFinished.isCompleted, "The close timeout must cancel stalled cleanup")
            assertTrue(job.isCompleted)
            assertTrue(job.isCancelled)
            assertEquals(1, recordingTransport.stopCalls)
        } finally {
            job.cancelAndJoin()
            events.cancel()
            close(client, session)
        }
    }
}

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `handshake failure is reported after close timeout when flush stalls`() = runTest {
        val flushStarted = CompletableDeferred<Unit>()
        val releaseFlush = CompletableDeferred<Unit>()
        val session = FakeWebSocketSession(
            handshakePayload = null,
            onFlush = {
                flushStarted.complete(Unit)
                releaseFlush.await()
            },
        )
        val client = testHttpClient()
        val connection = testConnection(
            client = client,
            session = session,
            handshakeResponseTimeout = 25.milliseconds,
            closeTimeout = 50.milliseconds,
        )
        val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
        val job = launchConnection(connection, events)

        try {
            runCurrent()
            advanceTimeBy(25.milliseconds)
            runCurrent()
            assertTrue(flushStarted.isCompleted, "Handshake failure must reach transport cleanup")
            assertFalse(job.isCompleted)

            advanceTimeBy(50.milliseconds)
            runCurrent()

            assertTrue(job.isCompleted, "Handshake failure must finish after closeTimeout despite a stalled flush")
            val states = mutableListOf<HubConnectionEvent.StateChanged>()
            while (true) {
                val event = events.tryReceive().getOrNull() ?: break
                if (event is HubConnectionEvent.StateChanged) {
                    states += event
                }
            }
            assertEquals(
                listOf(
                    HubConnectionState.CONNECTING,
                    HubConnectionState.DISCONNECTING,
                    HubConnectionState.DISCONNECTED,
                ),
                states.map { it.state },
            )
            val reason = assertIs<HubConnectionCloseReason.Failed>(states.last().reason)
            assertIs<IllegalStateException>(reason.cause)
            assertEquals(
                "Server timeout elapsed without receiving a handshake response.",
                reason.cause.message,
            )
        } finally {
            // Release the old, unbounded cleanup even when the regression assertion fails.
            releaseFlush.complete(Unit)
            job.cancelAndJoin()
            close(client, session)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling during handshake completes after close timeout when flush stalls`() = runTest {
        val flushStarted = CompletableDeferred<Unit>()
        val releaseFlush = CompletableDeferred<Unit>()
        val session = FakeWebSocketSession(
            handshakePayload = null,
            onFlush = {
                flushStarted.complete(Unit)
                releaseFlush.await()
            },
        )
        val client = testHttpClient()
        val connection = testConnection(
            client = client,
            session = session,
            closeTimeout = 50.milliseconds,
        )
        val events = Channel<HubConnectionEvent>(Channel.UNLIMITED)
        val job = launchConnection(connection, events)

        try {
            runCurrent()
            session.awaitTextMessage("{\"protocol\":\"json\",\"version\":1}$RECORD_SEPARATOR")
            job.cancel()
            runCurrent()
            assertTrue(flushStarted.isCompleted, "Cancellation must reach transport cleanup")
            assertFalse(job.isCompleted)

            advanceTimeBy(50.milliseconds)
            runCurrent()

            assertTrue(job.isCompleted, "Collector cancellation must finish after closeTimeout despite a stalled flush")
            assertTrue(job.isCancelled)
        } finally {
            releaseFlush.complete(Unit)
            job.cancelAndJoin()
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
    closeTimeout: Duration = 5.seconds,
): HubConnection = DefaultHubConnection(
    testOptions(
        client = client,
        session = session,
        logger = logger,
        keepAliveInterval = keepAliveInterval,
        handshakeResponseTimeout = handshakeResponseTimeout,
        closeTimeout = closeTimeout,
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
