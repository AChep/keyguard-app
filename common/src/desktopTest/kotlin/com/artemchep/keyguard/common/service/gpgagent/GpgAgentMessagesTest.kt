package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class GpgAgentMessagesTest {
    @Test
    fun `authentication revision round-trips and is emitted as field 2`() {
        val token = ByteArray(32) { it.toByte() }
        val request = AgentIpcMessages.AuthenticateRequest(
            token = token,
            protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
        )
        val response = AgentIpcMessages.AuthenticateResponse(
            success = true,
            protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
        )

        val decodedRequest = ProtoBuf.decodeFromByteArray<AgentIpcMessages.AuthenticateRequest>(
            ProtoBuf.encodeToByteArray(request),
        )
        val decodedResponse = ProtoBuf.decodeFromByteArray<AgentIpcMessages.AuthenticateResponse>(
            ProtoBuf.encodeToByteArray(response),
        )
        val revisionOnlyRequest = ProtoBuf.encodeToByteArray(
            AgentIpcMessages.AuthenticateRequest(
                protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
            ),
        )

        assertContentEquals(token, decodedRequest.token)
        assertEquals(AgentIpcMessages.PROTOCOL_REVISION, decodedRequest.protocolRevision)
        assertEquals(true, decodedResponse.success)
        assertEquals(AgentIpcMessages.PROTOCOL_REVISION, decodedResponse.protocolRevision)
        assertContentEquals(byteArrayOf(0x0a, 0x00, 0x10, 0x01), revisionOnlyRequest)
    }

    @Test
    fun `CallerAuthorization round-trips as field 9`() {
        val connection = ByteArray(32) { (255 - it).toByte() }
        val application = ByteArray(32) { (127 - it).toByte() }
        val original = GpgAgentMessages.IpcRequest(
            id = 11L,
            listKeys = GpgAgentMessages.ListKeysRequest(
                caller = CallerIdentity(
                    appName = "Terminal",
                    authorization = CallerAuthorization(
                        connectionFingerprint = connection,
                        subjects = listOf(
                            CallerAuthorizationSubject(
                                kind =
                                    AgentCallerAuthorizationSchema.SubjectKind.APPLICATION_INSTANCE,
                                evidenceSource =
                                    AgentCallerAuthorizationSchema.EvidenceSource
                                        .MACOS_APPLICATION_ANCESTRY,
                                fingerprint = application,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val encoded = ProtoBuf.encodeToByteArray(original)
        val decoded = ProtoBuf.decodeFromByteArray<GpgAgentMessages.IpcRequest>(encoded)
        val authorization = decoded.listKeys?.caller?.authorization

        assertContentEquals(connection, authorization?.connectionFingerprint)
        assertEquals(1, authorization?.subjects?.size)
        assertEquals(
            AgentCallerAuthorizationSchema.SubjectKind.APPLICATION_INSTANCE,
            authorization?.subjects?.single()?.kind,
        )
        assertContentEquals(application, authorization?.subjects?.single()?.fingerprint)
    }
}
