package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentDuplex
import com.artemchep.keyguard.common.service.agent.AgentDuplexChannel
import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.AgentIpcTestClient
import com.artemchep.keyguard.common.service.agent.AgentIpcTestLog
import com.artemchep.keyguard.common.service.agent.AgentIpcTestPeerGate
import com.artemchep.keyguard.common.service.agent.AgentIpcTestServer
import com.artemchep.keyguard.common.service.agent.authenticateCli
import com.artemchep.keyguard.common.service.agent.cliRunRequestPayload
import com.artemchep.keyguard.common.service.agent.pendingCliRunHandler
import com.artemchep.keyguard.common.service.agent.serveCliForTest
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop CLI agent's connections are bounded by the client's own budget:
 * a connection whose read or write stalls past it is closed. A request is not:
 * its approval prompt owns the deadline. The tests use 200 ms for the 30 s of
 * production.
 */
@OptIn(ExperimentalSerializationApi::class)
class CliAgentIpcServerTest {
    private val authToken = ByteArray(AgentIpcAuthenticator.TOKEN_SIZE) { it.toByte() }

    @Test
    fun `the budget is the CLI agent's own`() {
        // CONNECT_TIMEOUT in commonAgent/ipc/src/stream.rs.
        assertEquals(30_000L, CliAgentIpcServer.IO_TIMEOUT_MS)
    }

    @Test
    fun `a client that never authenticates is closed`() = withStalledCliServer { server, log, _ ->
        AgentIpcTestClient.connect(server.endpoint).use { client ->
            assertEquals(0, client.readToEnd(), "A stalled client must be closed unanswered")
        }
        server.stop()
        assertEquals(emptyList(), log.errors)
    }

    @Test
    fun `a client that never sends its request is closed`() = withStalledCliServer { server, log, _ ->
        AgentIpcTestClient.connect(server.endpoint).use { client ->
            assertTrue(client.authenticateCli(authToken))
            assertEquals(0, client.readToEnd(), "A stalled client must be closed unanswered")
        }
        server.stop()
        assertEquals(emptyList(), log.errors)
    }

    @Test
    fun `a request outlasts the budget until the client leaves`() = withStalledCliServer { server, log, run ->
        AgentIpcTestClient.connect(server.endpoint).use { client ->
            assertTrue(client.authenticateCli(authToken))
            client.writeFrame(cliRunRequestPayload())
            withTimeout(5_000L) { run.started.await() }
            // The client keeps its side open: the budget does not end the request.
            delay(1_000L)
            assertFalse(run.cancelled.isCompleted, "A request must outlast the budget")
        }
        // The client left, so the request is cancelled.
        withTimeout(5_000L) { run.cancelled.await() }
        server.stop()
        assertFalse(server.isServing)
        assertEquals(emptyList(), log.errors)
    }

    @Test
    fun `a browser stream stays open past the budget and carries frames both ways`() = runBlocking {
        val fixtureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val closed = CompletableDeferred<Unit>()
        try {
            val fixture = EnvironmentFixture(fixtureScope)
            val handler = fixture.agent(browser = { CliAgentAnswer.opened(EchoDuplex(closed)) })
            val log = AgentIpcTestLog()
            val server = serveCliForTest(
                log = log,
                authToken = authToken,
                handler = handler,
                gate = AgentIpcTestPeerGate().apply { open() },
                ioTimeoutMs = 200L,
            )
            try {
                AgentIpcTestClient.connect(server.endpoint).use { client ->
                    assertTrue(client.authenticateCli(authToken))
                    client.writeFrame(ProtoBuf.encodeToByteArray(CliAgentMessages.Request(browserOpenRequest())))
                    val answer = assertNotNull(client.readFrame())
                    val response = ProtoBuf.decodeFromByteArray<CliAgentMessages.Response>(answer)
                    assertIs<CliAgentMessages.Response.Kind.BrowserOpen>(response.response)
                    // The stream waits for the extension past the budget of a request.
                    delay(1_000L)
                    client.writeFrame("ping".encodeToByteArray())
                    assertEquals("echo ping", client.readFrame()?.decodeToString())
                    client.writeFrame("pong".encodeToByteArray())
                    assertEquals("echo pong", client.readFrame()?.decodeToString())
                }
                // The client left: the stream ends.
                withTimeout(5_000L) { closed.await() }
            } finally {
                server.stop()
            }
            assertEquals(emptyList(), log.errors)
        } finally {
            fixtureScope.cancel()
        }
    }

    /** Answers each frame with `echo <frame>` until the client leaves. */
    private class EchoDuplex(private val closed: CompletableDeferred<Unit>) : AgentDuplex {
        override val maxPacketSize: Int get() = 65_535

        override suspend fun serve(channel: AgentDuplexChannel) {
            while (true) {
                val packet = channel.readPacket()
                    ?: break
                channel.writePacket("echo ${packet.decodeToString()}".encodeToByteArray())
            }
        }

        override fun close() {
            closed.complete(Unit)
        }
    }

    private class PendingRun(
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        val cancelled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    /** Serves a CLI agent whose connections have 200 ms per phase, and whose `keyguard run` never ends. */
    private fun withStalledCliServer(
        block: suspend (AgentIpcTestServer, AgentIpcTestLog, PendingRun) -> Unit,
    ) = runBlocking {
        val fixtureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val run = PendingRun()
            val log = AgentIpcTestLog()
            val server = serveCliForTest(
                log = log,
                authToken = authToken,
                handler = pendingCliRunHandler(fixtureScope, run.started, run.cancelled),
                gate = AgentIpcTestPeerGate().apply { open() },
                ioTimeoutMs = 200L,
            )
            try {
                block(server, log, run)
            } finally {
                server.stop()
            }
        } finally {
            fixtureScope.cancel()
        }
    }
}
