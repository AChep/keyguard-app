package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
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
        val request = GpgAgentMessages.AuthenticateRequest(
            token = token,
            protocolRevision = GpgAgentMessages.PROTOCOL_REVISION,
        )
        val response = GpgAgentMessages.AuthenticateResponse(
            success = true,
            protocolRevision = GpgAgentMessages.PROTOCOL_REVISION,
        )

        val decodedRequest = ProtoBuf.decodeFromByteArray<GpgAgentMessages.AuthenticateRequest>(
            ProtoBuf.encodeToByteArray(request),
        )
        val decodedResponse = ProtoBuf.decodeFromByteArray<GpgAgentMessages.AuthenticateResponse>(
            ProtoBuf.encodeToByteArray(response),
        )
        val revisionOnlyRequest = ProtoBuf.encodeToByteArray(
            GpgAgentMessages.AuthenticateRequest(
                protocolRevision = GpgAgentMessages.PROTOCOL_REVISION,
            ),
        )

        assertContentEquals(token, decodedRequest.token)
        assertEquals(GpgAgentMessages.PROTOCOL_REVISION, decodedRequest.protocolRevision)
        assertEquals(true, decodedResponse.success)
        assertEquals(GpgAgentMessages.PROTOCOL_REVISION, decodedResponse.protocolRevision)
        assertContentEquals(byteArrayOf(0x0a, 0x00, 0x10, 0x01), revisionOnlyRequest)
    }

    @Test
    fun `CallerAuthorization round-trips as field 9`() {
        val connection = ByteArray(32) { (255 - it).toByte() }
        val application = ByteArray(32) { (127 - it).toByte() }
        val original = GpgAgentMessages.IpcRequest(
            id = 11L,
            listKeys = GpgAgentMessages.ListKeysRequest(
                caller = GpgAgentMessages.CallerIdentity(
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
