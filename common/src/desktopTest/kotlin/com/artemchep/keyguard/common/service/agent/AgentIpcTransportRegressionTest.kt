package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transport defects of the desktop agents' private channel, through the real
 * endpoint and a JDK client.
 */
class AgentIpcTransportRegressionTest {
    private val authToken = ByteArray(AgentIpcAuthenticator.TOKEN_SIZE) { it.toByte() }

    /**
     * A frame header whose body never comes is the end of the session, not a
     * request. The JDK socket transport failed on it; the JNA pipe transport
     * processed a zero-filled body.
     */
    @Test
    fun `a header then the end of the stream ends the session quietly`() = runBlocking {
        val log = AgentIpcTestLog()
        val gate = AgentIpcTestPeerGate().apply { open() }
        val server = serveSshForTest(log, authToken, gate)
        try {
            AgentIpcTestClient.connect(server.endpoint).use { client ->
                assertTrue(client.authenticateSsh(authToken, id = 1L))
                client.write(AgentIpcFraming.encodeHeader(16, AgentIpcFraming.MAX_PACKET_SIZE))
                client.endOutput()
                if (!isWindowsHost) {
                    assertEquals(0, client.readToEnd(), "The truncated frame must not be answered")
                }
            }
            AgentIpcTestClient.connect(server.endpoint).use { client ->
                assertTrue(client.authenticateSsh(authToken, id = 2L))
            }
        } finally {
            server.stop()
        }
        assertEquals(emptyList(), log.errors)
    }

    /**
     * A client that vanishes before its connection is accepted. The JNA pipe
     * transport of Windows stopped serving on it.
     */
    @Test
    fun `a client that connects and closes while the gate is closed does not stop the server`() = runBlocking {
        val log = AgentIpcTestLog()
        val gate = AgentIpcTestPeerGate()
        val server = serveSshForTest(log, authToken, gate)
        try {
            AgentIpcTestClient.connect(server.endpoint).close()
            gate.open()
            AgentIpcTestClient.connect(server.endpoint).use { client ->
                assertTrue(client.authenticateSsh(authToken, id = 1L))
            }
            assertTrue(server.isServing, "The server must keep serving")
        } finally {
            server.stop()
        }
    }

    /**
     * A byte of unexpected input, not a whole frame, while the one CLI request
     * is pending. The JDK socket transport of Unix waited for a whole frame.
     */
    @Test
    fun `a partial header during a pending CLI request cancels it`() = runBlocking {
        val fixtureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val handler = pendingCliRunHandler(fixtureScope, started, cancelled)

            val gate = AgentIpcTestPeerGate().apply { open() }
            val server = serveCliForTest(AgentIpcTestLog(), authToken, handler, gate)
            try {
                AgentIpcTestClient.connect(server.endpoint).use { client ->
                    assertTrue(client.authenticateCli(authToken))
                    client.writeFrame(cliRunRequestPayload())
                    withTimeout(5_000L) { started.await() }
                    client.write(byteArrayOf(0))
                    withTimeout(5_000L) { cancelled.await() }
                    assertEquals(0, client.readToEnd(), "A cancelled request must not be answered")
                }
            } finally {
                server.stop()
            }
        } finally {
            fixtureScope.cancel()
        }
    }
}
