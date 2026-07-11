package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GpgAgentApprovalCacheKeyTest {
    @Test
    fun `legacy callers never produce reusable gpg cache keys`() {
        val first = GpgAgentMessages.CallerIdentity(appName = "Terminal")
        val second = GpgAgentMessages.CallerIdentity(appName = "Terminal")

        assertNull(gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", first))
        assertNull(gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", second))
    }

    @Test
    fun `gpg cache key uses connection identity and ignores display name`() {
        val first = caller(appName = "Terminal", fingerprintByte = 1)
        val renamed = caller(appName = "Spoofed Terminal", fingerprintByte = 1)
        val differentPrincipal = caller(appName = "Terminal", fingerprintByte = 2)

        val firstKey = gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", first)
        val renamedKey = gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", renamed)
        val differentPrincipalKey =
            gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", differentPrincipal)

        assertEquals(firstKey, renamedKey)
        assertNotEquals(firstKey, differentPrincipalKey)
    }

    @Test
    fun `malformed gpg authorization never produces a cache key`() {
        val caller = GpgAgentMessages.CallerIdentity(
            appName = "Terminal",
            authorization = CallerAuthorization(
                connectionFingerprint = ByteArray(31),
            ),
        )

        assertNull(gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", caller))
    }

    @Test
    fun `gpg cache reuses verified subject across connections but partitions context`() {
        val first = separatedCaller(connectionByte = 1, contextByte = 2)
        val anotherConnection = separatedCaller(connectionByte = 3, contextByte = 2)
        val anotherContext = separatedCaller(connectionByte = 3, contextByte = 4)

        val firstKey = gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", first)
        val anotherConnectionKey =
            gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", anotherConnection)
        val anotherContextKey =
            gpgApprovalCacheKey(GpgAgentOperation.SIGN, "ABC", anotherContext)

        assertEquals(firstKey, anotherConnectionKey)
        assertNotEquals(firstKey, anotherContextKey)
    }

    private fun caller(
        appName: String,
        fingerprintByte: Byte,
    ) = GpgAgentMessages.CallerIdentity(
        appName = appName,
        authorization = CallerAuthorization(
            connectionFingerprint = ByteArray(32) { fingerprintByte },
        ),
    )

    private fun separatedCaller(
        connectionByte: Byte,
        contextByte: Byte,
    ) = GpgAgentMessages.CallerIdentity(
        appName = "Terminal",
        authorization = CallerAuthorization(
            connectionFingerprint = ByteArray(32) { connectionByte },
            subjects = listOf(
                CallerAuthorizationSubject(
                    kind = AgentCallerAuthorizationSchema.SubjectKind.PROCESS,
                    evidenceSource =
                        AgentCallerAuthorizationSchema.EvidenceSource.MACOS_AUDIT_TOKEN,
                    fingerprint = ByteArray(32) { 5 },
                ),
            ),
            authorizationContextFingerprint = ByteArray(32) { contextByte },
        ),
    )
}
