package com.artemchep.keyguard.android

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPendingSession
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthError
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneNodeResolution
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProtocol
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthEncryptedPayload
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthResponse
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthStatus
import com.artemchep.keyguard.feature.auth.companion.canAcceptIncomingChannel
import com.artemchep.keyguard.feature.auth.companion.isCompanionAuthResponsePayloadWithinLimits
import com.artemchep.keyguard.feature.auth.companion.isValidWatchResponseSession
import com.artemchep.keyguard.feature.auth.companion.parseCompanionAuthIncomingChannelPath
import com.artemchep.keyguard.feature.auth.companion.resolveCompanionPhoneNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionAuthCoordinatorSecurityTest {
    @Test
    fun `watch response session validation requires expected node and initiator role`() {
        val response = CompanionAuthResponse(
            requestId = "request-a",
            provider = CompanionAuthProvider.BITWARDEN,
            protocolVersion = CompanionAuthProtocol.VERSION,
            status = CompanionAuthStatus.STARTED,
            phonePublicKey = "phone-public-key",
        )

        assertTrue(
            isValidWatchResponseSession(
                session = initiatorSession(),
                nodeId = "phone-node",
                response = response,
            ),
        )
        assertFalse(
            isValidWatchResponseSession(
                session = initiatorSession(),
                nodeId = "rogue-node",
                response = response,
            ),
        )
        assertFalse(
            isValidWatchResponseSession(
                session = receiverSession(),
                nodeId = "phone-node",
                response = response,
            ),
        )
    }

    @Test
    fun `watch response session validation allows unsupported protocol error mismatch`() {
        val response = CompanionAuthResponse(
            requestId = "request-a",
            provider = CompanionAuthProvider.BITWARDEN,
            protocolVersion = CompanionAuthProtocol.VERSION + 1,
            status = CompanionAuthStatus.ERROR,
            error = CompanionAuthError.UNSUPPORTED_PROTOCOL,
        )

        assertTrue(
            isValidWatchResponseSession(
                session = initiatorSession(),
                nodeId = "phone-node",
                response = response,
            ),
        )
        assertFalse(
            isValidWatchResponseSession(
                session = initiatorSession(),
                nodeId = "rogue-node",
                response = response,
            ),
        )
    }

    @Test
    fun `watch response session validation rejects non protocol error mismatch`() {
        val response = CompanionAuthResponse(
            requestId = "request-a",
            provider = CompanionAuthProvider.BITWARDEN,
            protocolVersion = CompanionAuthProtocol.VERSION + 1,
            status = CompanionAuthStatus.ERROR,
            error = CompanionAuthError.REQUEST_FAILED,
        )

        assertFalse(
            isValidWatchResponseSession(
                session = initiatorSession(),
                nodeId = "phone-node",
                response = response,
            ),
        )
    }

    @Test
    fun `incoming keepass channels require keepass initiator session remote key and expected node`() {
        assertTrue(
            canAcceptIncomingChannel(
                session = initiatorSession(
                    provider = CompanionAuthProvider.KEEPASS,
                    remotePublicKeyBase64 = "phone-public-key",
                ),
                nodeId = "phone-node",
            ),
        )
        assertFalse(
            canAcceptIncomingChannel(
                session = initiatorSession(
                    provider = CompanionAuthProvider.BITWARDEN,
                    remotePublicKeyBase64 = "phone-public-key",
                ),
                nodeId = "phone-node",
            ),
        )
        assertFalse(
            canAcceptIncomingChannel(
                session = initiatorSession(
                    provider = CompanionAuthProvider.KEEPASS,
                    remotePublicKeyBase64 = null,
                ),
                nodeId = "phone-node",
            ),
        )
        assertFalse(
            canAcceptIncomingChannel(
                session = initiatorSession(
                    provider = CompanionAuthProvider.KEEPASS,
                    remotePublicKeyBase64 = "phone-public-key",
                ),
                nodeId = "rogue-node",
            ),
        )
    }

    @Test
    fun `reachable phone selection respects explicit selection and auto-selects a single device`() {
        assertEquals(
            CompanionAuthPhoneNodeResolution.Available("phone-a"),
            resolveCompanionPhoneNodeId(
                reachableNodeIds = listOf("phone-a"),
                selectedNodeId = null,
            ),
        )
        assertEquals(
            CompanionAuthPhoneNodeResolution.Available("phone-b"),
            resolveCompanionPhoneNodeId(
                reachableNodeIds = listOf("phone-a", "phone-b"),
                selectedNodeId = "phone-b",
            ),
        )
        assertEquals(
            CompanionAuthError.MULTIPLE_REACHABLE_PHONES,
            (resolveCompanionPhoneNodeId(
                reachableNodeIds = listOf("phone-a", "phone-b"),
                selectedNodeId = null,
            ) as CompanionAuthPhoneNodeResolution.Failed).error,
        )
        assertEquals(
            CompanionAuthError.PHONE_UNAVAILABLE,
            (resolveCompanionPhoneNodeId(
                reachableNodeIds = listOf("phone-a"),
                selectedNodeId = "phone-b",
            ) as CompanionAuthPhoneNodeResolution.Failed).error,
        )
    }

    @Test
    fun `incoming channel path rejects malformed request ids`() {
        assertNull(
            parseCompanionAuthIncomingChannelPath(
                "/companion-auth/channel/not-a-uuid/database",
            ),
        )
        assertTrue(
            parseCompanionAuthIncomingChannelPath(
                "/companion-auth/channel/123e4567-e89b-12d3-a456-426614174000/database",
            ) != null,
        )
    }

    @Test
    fun `response payload limit rejects oversized ciphertext`() {
        val withinLimit = CompanionAuthResponse(
            requestId = "123e4567-e89b-12d3-a456-426614174000",
            provider = CompanionAuthProvider.BITWARDEN,
            protocolVersion = CompanionAuthProtocol.VERSION,
            status = CompanionAuthStatus.SUCCESS,
            encryptedPayload = CompanionAuthEncryptedPayload(
                cipherText = "a".repeat(128),
            ),
        )
        val oversized = withinLimit.copy(
            encryptedPayload = CompanionAuthEncryptedPayload(
                cipherText = "a".repeat(CompanionAuthProtocol.MAX_RESPONSE_CIPHERTEXT_BYTES + 1),
            ),
        )

        assertTrue(isCompanionAuthResponsePayloadWithinLimits(withinLimit))
        assertFalse(isCompanionAuthResponsePayloadWithinLimits(oversized))
    }
}

private fun initiatorSession(
    provider: CompanionAuthProvider = CompanionAuthProvider.BITWARDEN,
    remotePublicKeyBase64: String? = "phone-public-key",
) = CompanionAuthPendingSession(
    requestId = "123e4567-e89b-12d3-a456-426614174000",
    provider = provider,
    role = CompanionAuthPendingSession.Role.Initiator,
    localNodeId = "watch-node",
    expectedNodeId = "phone-node",
    protocolVersion = CompanionAuthProtocol.VERSION,
    createdAtEpochMillis = System.currentTimeMillis(),
    localPrivateKeyBase64 = "watch-private-key",
    localPublicKeyBase64 = "watch-public-key",
    remotePublicKeyBase64 = remotePublicKeyBase64,
)

private fun receiverSession() = CompanionAuthPendingSession(
    requestId = "123e4567-e89b-12d3-a456-426614174000",
    provider = CompanionAuthProvider.BITWARDEN,
    role = CompanionAuthPendingSession.Role.Receiver,
    localNodeId = "phone-node",
    expectedNodeId = "watch-node",
    protocolVersion = CompanionAuthProtocol.VERSION,
    createdAtEpochMillis = System.currentTimeMillis(),
    localPrivateKeyBase64 = "phone-private-key",
    localPublicKeyBase64 = "phone-public-key",
    remotePublicKeyBase64 = "watch-public-key",
)
