package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class CallerAuthorizationWireTest {
    @Test
    fun `shared authorization keeps the exact protobuf wire contract`() {
        val connectionFingerprint = ByteArray(32) { (it + 1).toByte() }
        val subjectFingerprint = ByteArray(32) { (it + 33).toByte() }
        val contextFingerprint = ByteArray(32) { (it + 65).toByte() }
        val authorization =
            CallerAuthorization(
                connectionFingerprint = connectionFingerprint,
                subjects =
                    listOf(
                        CallerAuthorizationSubject(
                            kind = AgentCallerAuthorizationSchema.SubjectKind.APPLICATION_INSTANCE,
                            evidenceSource =
                                AgentCallerAuthorizationSchema.EvidenceSource.MACOS_APPLICATION_ANCESTRY,
                            fingerprint = subjectFingerprint,
                        ),
                    ),
                authorizationContextFingerprint = contextFingerprint,
            )

        val expected =
            byteArrayOf(0x32, 0x20) +
                connectionFingerprint +
                byteArrayOf(0x3a, 0x26, 0x08, 0x02, 0x10, 0x0c, 0x1a, 0x20) +
                subjectFingerprint +
                byteArrayOf(0x42, 0x20) +
                contextFingerprint

        assertContentEquals(expected, ProtoBuf.encodeToByteArray(authorization))
    }

    @Test
    fun `SSH and GPG callers serialize shared authorization identically`() {
        val authorization =
            CallerAuthorization(
                connectionFingerprint = ByteArray(32) { 1 },
                subjects =
                    listOf(
                        CallerAuthorizationSubject(
                            kind = AgentCallerAuthorizationSchema.SubjectKind.PROCESS,
                            evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.LINUX_PIDFD,
                            fingerprint = ByteArray(32) { 2 },
                        ),
                    ),
                authorizationContextFingerprint = ByteArray(32) { 3 },
            )
        val sshBytes =
            ProtoBuf.encodeToByteArray(
                SshAgentMessages.CallerIdentity(authorization = authorization),
            )
        val gpgBytes =
            ProtoBuf.encodeToByteArray(
                GpgAgentMessages.CallerIdentity(authorization = authorization),
            )

        assertContentEquals(sshBytes, gpgBytes)
        assertEquals(
            authorization,
            ProtoBuf.decodeFromByteArray<SshAgentMessages.CallerIdentity>(sshBytes).authorization,
        )
        assertEquals(
            authorization,
            ProtoBuf.decodeFromByteArray<GpgAgentMessages.CallerIdentity>(gpgBytes).authorization,
        )
    }
}
