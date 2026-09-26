package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class GpgOpenPgpRingSelectionTest {
    @Test
    fun `preferred signing key does not narrow an unconstrained chooser`() {
        val first = ring(
            cipherId = "first",
            fingerprint = FIRST_FINGERPRINT,
            email = "first@example.test",
        )
        val second = ring(
            cipherId = "second",
            fingerprint = SECOND_FINGERPRINT,
            email = "second@example.test",
        )

        val candidates = gpgOpenPgpApprovalCandidates(
            kind = GpgOpenPgpOperationKind.GET_SIGN_KEY_ID,
            vault = vault(first, second),
            requestedEmails = emptyList(),
            keyIds = emptyList(),
            preferredKeyIds = listOf(first.primaryKeyId),
        )

        assertEquals(
            listOf("first", "second"),
            candidates.map(GpgOpenPgpRing::cipherId),
        )
    }

    @Test
    fun `preferred signing key augments email matches without admitting unrelated keys`() {
        val emailMatch = ring(
            cipherId = "email-match",
            fingerprint = FIRST_FINGERPRINT,
            email = "alice@example.test",
        )
        val preferred = ring(
            cipherId = "preferred",
            fingerprint = SECOND_FINGERPRINT,
            email = "other@example.test",
        )
        val unrelated = ring(
            cipherId = "unrelated",
            fingerprint = THIRD_FINGERPRINT,
            email = "unrelated@example.test",
        )

        val candidates = gpgOpenPgpApprovalCandidates(
            kind = GpgOpenPgpOperationKind.GET_SIGN_KEY_ID,
            vault = vault(emailMatch, preferred, unrelated),
            requestedEmails = listOf("alice@example.test"),
            keyIds = emptyList(),
            preferredKeyIds = listOf(preferred.primaryKeyId),
        )

        assertEquals(
            listOf("email-match", "preferred"),
            candidates.map(GpgOpenPgpRing::cipherId),
        )
    }

    @Test
    fun `missing and unusable preferred signing keys are not offered`() {
        val available = ring(
            cipherId = "available",
            fingerprint = FIRST_FINGERPRINT,
            email = "alice@example.test",
        )
        val unusable = ring(
            cipherId = "unusable",
            fingerprint = SECOND_FINGERPRINT,
            email = "other@example.test",
            hasSigningPrivateMaterial = false,
        )

        val candidates = gpgOpenPgpApprovalCandidates(
            kind = GpgOpenPgpOperationKind.GET_SIGN_KEY_ID,
            vault = vault(available, unusable),
            requestedEmails = listOf("alice@example.test"),
            keyIds = emptyList(),
            preferredKeyIds = listOf(unusable.primaryKeyId, MISSING_KEY_ID),
        )

        assertEquals(
            listOf("available"),
            candidates.map(GpgOpenPgpRing::cipherId),
        )
    }

    private fun vault(
        vararg rings: GpgOpenPgpRing,
    ) = GpgOpenPgpVault(
        session = null,
        rings = rings.toList(),
        certificationAuthorities = emptyList(),
    )

    private fun ring(
        cipherId: String,
        fingerprint: String,
        email: String,
        hasSigningPrivateMaterial: Boolean = true,
    ) = GpgOpenPgpRing(
        accountId = "account",
        cipherId = cipherId,
        name = cipherId,
        info = GpgPublicKeyInfo(
            fingerprint = fingerprint,
            keyId = fingerprint.takeLast(16),
            algorithm = "EdDSA",
            bitStrength = 255,
            userIds = listOf("$cipherId <$email>"),
            emails = listOf(email),
            createdAt = null,
            expiresAt = null,
            revoked = false,
            canSign = true,
            canEncrypt = false,
            publicKeyArmored = "public-$cipherId",
            subKeys = emptyList(),
        ),
        hasSigningPrivateMaterial = hasSigningPrivateMaterial,
        hasDecryptionPrivateMaterial = false,
        privateKeyArmored = "private-$cipherId".takeIf { hasSigningPrivateMaterial },
        now = NOW,
    )

    private companion object {
        val NOW = Instant.fromEpochMilliseconds(1_000L)
        const val FIRST_FINGERPRINT = "0000000000000000000000000000000000000001"
        const val SECOND_FINGERPRINT = "0000000000000000000000000000000000000002"
        const val THIRD_FINGERPRINT = "0000000000000000000000000000000000000003"
        const val MISSING_KEY_ID = 4L
    }
}
