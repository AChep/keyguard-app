package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.cli.CliServiceException
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.ManageResponse
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Request
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages.Response
import com.artemchep.keyguard.common.service.cli.agent.CliAgentPacketSession
import com.artemchep.keyguard.common.service.cli.agent.manageResult
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
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalSerializationApi::class)
class EnvironmentAgentChannelTest {
    @Test
    fun `private packet channel rejects another protocol revision`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val packetSession = CliAgentPacketSession(fixture.agent(), ByteArray(32))
        val authenticate = Request.Kind.Authenticate(
            AgentIpcMessages.AuthenticateRequest(
                token = ByteArray(32),
                protocolRevision = AgentIpcMessages.PROTOCOL_REVISION + 1,
            ),
        )
        val refused = packetSession.process(ProtoBuf.encodeToByteArray(Request(authenticate)))
        assertTrue(refused.close)
        assertEquals(rejected, ProtoBuf.decodeFromByteArray<Response>(refused.packet).response)
    }

    @Test
    fun `private packet channel rejects unauthenticated and malformed requests safely`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        val handler = fixture.agent()
        val unauthenticated = CliAgentPacketSession(handler, ByteArray(32))
        val denied = unauthenticated.process(ProtoBuf.encodeToByteArray(Request(resolveMount("epoch"))))
        assertTrue(denied.close)
        assertEquals(rejected, ProtoBuf.decodeFromByteArray<Response>(denied.packet).response)
        val malformed = CliAgentPacketSession(handler, ByteArray(32))
        val error = assertFailsWith<CliServiceException> {
            malformed.process("{broken:$SECRET".encodeToByteArray())
        }
        assertEquals("invalid_request", error.code)
        assertFalse(SECRET in error.toString())
        assertTrue(fixture.saved.isEmpty())
    }

    @Test
    fun `private packet channel authenticates its token and returns only management metadata`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        fixture.ready()
        fixture.autoApprove(backgroundScope)
        val packetSession = CliAgentPacketSession(fixture.agent(), ByteArray(32))
        val authenticate = Request.Kind.Authenticate(
            AgentIpcMessages.AuthenticateRequest(
                token = ByteArray(32),
                protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
            ),
        )
        val authenticated = packetSession.process(ProtoBuf.encodeToByteArray(Request(authenticate)))
        assertFalse(authenticated.close)
        val response = packetSession.process(ProtoBuf.encodeToByteArray(Request(manage("list_variables"))))
        val result = ProtoBuf.decodeFromByteArray<Response>(response.packet).response.manageResult()
        assertEquals("TOKEN", assertIs<ManageResponse.Result.Variables>(result).value.variables.single().name)
        val content = response.packet.decodeToString()
        assertFalse(SECRET in content)
        assertFalse("secret description" in content)
        packetSession.close()
    }

    /** The app's answer to a first request that does not authenticate. */
    private val rejected = Response.Kind.Authenticate(
        AgentIpcMessages.AuthenticateResponse(success = false, protocolRevision = AgentIpcMessages.PROTOCOL_REVISION),
    )
}
