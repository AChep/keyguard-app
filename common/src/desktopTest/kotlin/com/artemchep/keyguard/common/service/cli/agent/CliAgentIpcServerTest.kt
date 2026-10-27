package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentIpcAuthenticator
import com.artemchep.keyguard.common.service.agent.AgentIpcTestClient
import com.artemchep.keyguard.common.service.agent.AgentIpcTestLog
import com.artemchep.keyguard.common.service.agent.AgentIpcTestPeerGate
import com.artemchep.keyguard.common.service.agent.AgentIpcTestServer
import com.artemchep.keyguard.common.service.agent.authenticateCli
import com.artemchep.keyguard.common.service.agent.cliRunRequestPayload
import com.artemchep.keyguard.common.service.agent.pendingCliRunHandler
import com.artemchep.keyguard.common.service.agent.serveCliForTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop CLI agent's connections are bounded by the client's own budget:
 * a connection that stalls past it is closed. The tests use 200 ms for the 70 s
 * of production.
 */
class CliAgentIpcServerTest {
    private val authToken = ByteArray(AgentIpcAuthenticator.TOKEN_SIZE) { it.toByte() }

    @Test
    fun `the budget is the CLI agent's own`() {
        // desktopCliAgent/src/src/app_client.rs gives up on a request after 70 s.
        assertEquals(70_000L, CliAgentIpcServer.IO_TIMEOUT_MS)
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
    fun `a request that outlasts the budget is cancelled and closed`() = withStalledCliServer { server, log, run ->
        AgentIpcTestClient.connect(server.endpoint).use { client ->
            assertTrue(client.authenticateCli(authToken))
            client.writeFrame(cliRunRequestPayload())
            withTimeout(5_000L) { run.started.await() }
            // The client keeps its side open: only the budget ends the request.
            withTimeout(5_000L) { run.cancelled.await() }
            assertEquals(0, client.readToEnd(), "A cancelled request must not be answered")
        }
        server.stop()
        assertFalse(server.isServing)
        assertEquals(emptyList(), log.errors)
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
