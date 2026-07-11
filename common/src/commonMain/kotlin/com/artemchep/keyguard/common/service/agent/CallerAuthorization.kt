package com.artemchep.keyguard.common.service.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Wire-level authorization evidence shared by every agent protocol.
 *
 * Field numbers intentionally match the shared protobuf schemas.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CallerAuthorization(
    @ProtoNumber(6)
    override val connectionFingerprint: ByteArray = byteArrayOf(),
    @ProtoNumber(7)
    override val subjects: List<CallerAuthorizationSubject> = emptyList(),
    @ProtoNumber(8)
    override val authorizationContextFingerprint: ByteArray = byteArrayOf(),
) : AgentCallerAuthorization {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is CallerAuthorization &&
                    connectionFingerprint.contentEquals(other.connectionFingerprint) &&
                    subjects == other.subjects &&
                    authorizationContextFingerprint.contentEquals(other.authorizationContextFingerprint)
            )

    override fun hashCode(): Int {
        var result = connectionFingerprint.contentHashCode()
        result = 31 * result + subjects.hashCode()
        result = 31 * result + authorizationContextFingerprint.contentHashCode()
        return result
    }
}

/** One independently verified caller subject on the shared agent wire. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CallerAuthorizationSubject(
    @ProtoNumber(1)
    override val kind: Int = AgentCallerAuthorizationSchema.SubjectKind.UNSPECIFIED,
    @ProtoNumber(2)
    override val evidenceSource: Int = AgentCallerAuthorizationSchema.EvidenceSource.UNSPECIFIED,
    @ProtoNumber(3)
    override val fingerprint: ByteArray = byteArrayOf(),
) : AgentCallerAuthorizationSubject {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is CallerAuthorizationSubject &&
                    kind == other.kind &&
                    evidenceSource == other.evidenceSource &&
                    fingerprint.contentEquals(other.fingerprint)
            )

    override fun hashCode(): Int {
        var result = kind
        result = 31 * result + evidenceSource
        result = 31 * result + fingerprint.contentHashCode()
        return result
    }
}
