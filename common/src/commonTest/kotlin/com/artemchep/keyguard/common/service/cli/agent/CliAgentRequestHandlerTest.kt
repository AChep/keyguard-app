package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.agent.AgentDuplex
import com.artemchep.keyguard.common.service.agent.AgentDuplexChannel
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalSerializationApi::class)
class CliAgentRequestHandlerTest {
    @Test
    fun `requests are routed to the feature that serves them`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val handler = fixture.agent()
        // This build does not support the CLI, which only its dispatcher knows.
        val cli = handler.respond(CliAgentMessages.Request.Kind.Cli(runRequest()))
        assertEquals("unsupported_platform", cli.error())
        val mounts = handler.respond(CliAgentMessages.Request.Kind.Mounts(CliAgentMessages.Empty))
        assertIs<CliAgentMessages.Response.Kind.Mounts>(mounts)
    }

    @Test
    fun `browser streams are refused while the integration is off`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        assertEquals("integration_disabled", fixture.agent().respond(browserOpenRequest()).error())
    }

    @Test
    fun `an accepted browser stream comes with the reply`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val stream = IdleStream()
        val session = authenticatedSession(fixture.agent(browser = { CliAgentAnswer.opened(stream) }))
        val reply = session.process(ProtoBuf.encodeToByteArray(CliAgentMessages.Request(browserOpenRequest())))
        assertFalse(reply.close)
        assertSame(stream, reply.upgrade)
        val response = ProtoBuf.decodeFromByteArray<CliAgentMessages.Response>(reply.packet).response
        assertIs<CliAgentMessages.Response.Kind.BrowserOpen>(response)
        assertEquals(0, stream.closed)
        session.close()
    }

    @Test
    fun `an opened stream always comes with its upgrade`() {
        assertFailsWith<IllegalArgumentException> {
            CliAgentAnswer(CliAgentMessages.Response.Kind.BrowserOpen(CliAgentMessages.Empty))
        }
    }

    @Test
    fun `a connection authenticates only once`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val request = CliAgentMessages.Request.Kind.Authenticate(AgentIpcMessages.AuthenticateRequest())
        assertEquals("invalid_request", fixture.agent().respond(request).error())
    }

    /** An authenticated session over [handler], as the transport sees it. */
    private suspend fun authenticatedSession(handler: CliAgentRequestHandler): CliAgentPacketSession {
        val session = CliAgentPacketSession(handler, ByteArray(32))
        val authenticate = CliAgentMessages.Request.Kind.Authenticate(
            AgentIpcMessages.AuthenticateRequest(
                token = ByteArray(32),
                protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
            ),
        )
        assertNull(session.process(ProtoBuf.encodeToByteArray(CliAgentMessages.Request(authenticate))).upgrade)
        return session
    }

    /** A stream that counts how often it is closed. */
    private class IdleStream : AgentDuplex {
        var closed = 0

        override val maxPacketSize = 64

        override suspend fun serve(channel: AgentDuplexChannel) = Unit

        override fun close() {
            closed += 1
        }
    }
}
