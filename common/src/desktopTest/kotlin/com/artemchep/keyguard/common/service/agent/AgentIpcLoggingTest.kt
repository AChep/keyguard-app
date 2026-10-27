package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.sshagent.SshAgentProtoCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a failed session of the desktop agents logs: fixed text, never the
 * message of the codec that read the helper's bytes.
 */
class AgentIpcLoggingTest {
    private val authToken = ByteArray(AgentIpcAuthenticator.TOKEN_SIZE) { it.toByte() }

    /** Not a protobuf message: a field number of 0. */
    private val undecodable = ByteArray(16)

    @Test
    fun `an undecodable SSH request logs no decoder text`() = runBlocking {
        val decoderError = assertNotNull(
            runCatching { SshAgentProtoCodec.decodeRequest(undecodable) }.exceptionOrNull(),
        )
        val decoderMessage = assertNotNull(decoderError.message)

        val log = AgentIpcTestLog()
        val server = serveSshForTest(log, authToken, AgentIpcTestPeerGate().apply { open() })
        try {
            AgentIpcTestClient.connect(server.endpoint).use { client ->
                assertTrue(client.authenticateSsh(authToken, id = 1L))
                client.writeFrame(undecodable)
                assertEquals(0, client.readToEnd(), "An undecodable request must not be answered")
            }
        } finally {
            // Every session has ended, and logged, once the server stops.
            server.stop()
        }

        assertNoLineContains(log, decoderMessage)
        assertContains(log.errors, "IPC session failed: ${decoderError::class.simpleName}")
    }

    /** The CLI session never let decoder text out; this guards it. */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `an undecodable CLI request logs no decoder text`() = runBlocking {
        val decoderMessage = assertNotNull(
            runCatching {
                ProtoBuf.decodeFromByteArray<CliAgentMessages.Request>(undecodable)
            }.exceptionOrNull()?.message,
        )

        val fixtureScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val log = AgentIpcTestLog()
        try {
            val handler = EnvironmentFixture(fixtureScope).agent()
            val server = serveCliForTest(log, authToken, handler, AgentIpcTestPeerGate().apply { open() })
            try {
                AgentIpcTestClient.connect(server.endpoint).use { client ->
                    assertTrue(client.authenticateCli(authToken))
                    client.writeFrame(undecodable)
                    assertEquals(0, client.readToEnd(), "An undecodable request must not be answered")
                }
            } finally {
                server.stop()
            }
        } finally {
            fixtureScope.cancel()
        }

        assertNoLineContains(log, decoderMessage)
        assertTrue(log.errors.isNotEmpty(), "The failed session must be logged")
    }

    private fun assertNoLineContains(log: AgentIpcTestLog, text: String) {
        val leaked = log.lines.filter { (_, line) -> text in line }
        assertEquals(emptyList<Pair<LogLevel, String>>(), leaked, "Decoder text must not be logged")
    }
}
