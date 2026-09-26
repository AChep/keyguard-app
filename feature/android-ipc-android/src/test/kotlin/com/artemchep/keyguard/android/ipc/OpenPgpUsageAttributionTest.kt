package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpRing
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpReadFileResult
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class OpenPgpUsageAttributionTest {
    @Test
    fun `resolves primary and subkey fingerprints to their owning ring`() {
        val ring = ring(
            cipherId = "cipher-a",
            primaryFingerprint = PRIMARY_FINGERPRINT,
            subKeyFingerprint = SUBKEY_FINGERPRINT,
        )

        val primary = resolveOpenPgpUsageIdentity(
            rings = listOf(ring),
            fingerprint = PRIMARY_FINGERPRINT.lowercase(),
        )
        val subKey = resolveOpenPgpUsageIdentity(
            rings = listOf(ring),
            fingerprint = SUBKEY_FINGERPRINT.chunked(4).joinToString(" "),
        )

        assertEquals("cipher-a", primary?.ring?.cipherId)
        assertEquals(PRIMARY_FINGERPRINT, primary?.fingerprint)
        assertEquals(PRIMARY_KEYGRIP, primary?.keygrip)
        assertEquals("cipher-a", subKey?.ring?.cipherId)
        assertEquals(SUBKEY_FINGERPRINT, subKey?.fingerprint)
        assertEquals(SUBKEY_KEYGRIP, subKey?.keygrip)
    }

    @Test
    fun `does not guess when attribution is missing or ambiguous`() {
        val first = ring("cipher-a", PRIMARY_FINGERPRINT, SUBKEY_FINGERPRINT)
        val duplicate = ring("cipher-b", "C".repeat(40), SUBKEY_FINGERPRINT)

        assertNull(resolveOpenPgpUsageIdentity(listOf(first), null))
        assertNull(resolveOpenPgpUsageIdentity(listOf(first), ""))
        assertNull(resolveOpenPgpUsageIdentity(listOf(first), "D".repeat(40)))
        assertNull(
            resolveOpenPgpUsageIdentity(
                rings = listOf(first, duplicate),
                fingerprint = SUBKEY_FINGERPRINT,
            ),
        )
    }

    @Test
    fun `attributes only encrypted message results`() {
        val ring = ring("cipher-a", PRIMARY_FINGERPRINT, SUBKEY_FINGERPRINT)
        val encrypted = GpgOpenPgpReadFileResult.Message(
            encrypted = true,
            decryptionKeyFingerprint = SUBKEY_FINGERPRINT,
        )
        val signedOnly = GpgOpenPgpReadFileResult.Message(
            encrypted = false,
            decryptionKeyFingerprint = SUBKEY_FINGERPRINT,
        )
        val clearSigned = GpgOpenPgpReadFileResult.ClearSigned(
            verification = GpgOpenPgpVerification(
                status = GpgOpenPgpVerificationStatus.VALID,
                keyId = SUBKEY_FINGERPRINT.takeLast(16),
                fingerprint = SUBKEY_FINGERPRINT,
                userIds = emptyList(),
                createdAt = null,
            ),
            bodyValidUtf8 = true,
            bodySize = 1L,
        )

        assertEquals(
            SUBKEY_FINGERPRINT,
            resolveOpenPgpDecryptionUsageIdentity(listOf(ring), encrypted)?.fingerprint,
        )
        assertNull(resolveOpenPgpDecryptionUsageIdentity(listOf(ring), signedOnly))
        assertNull(resolveOpenPgpDecryptionUsageIdentity(listOf(ring), clearSigned))
    }

    private fun ring(
        cipherId: String,
        primaryFingerprint: String,
        subKeyFingerprint: String,
    ) = GpgOpenPgpRing(
        accountId = "account",
        cipherId = cipherId,
        name = cipherId,
        info = GpgPublicKeyInfo(
            fingerprint = primaryFingerprint,
            keygrip = PRIMARY_KEYGRIP,
            keyId = primaryFingerprint.takeLast(16),
            algorithm = "ed25519",
            bitStrength = 255,
            userIds = emptyList(),
            emails = emptyList(),
            createdAt = null,
            expiresAt = null,
            revoked = false,
            canSign = true,
            canEncrypt = false,
            publicKeyArmored = "public",
            subKeys = listOf(
                GpgPublicSubKeyInfo(
                    fingerprint = subKeyFingerprint,
                    keygrip = SUBKEY_KEYGRIP,
                    keyId = subKeyFingerprint.takeLast(16),
                    algorithm = "x25519",
                    bitStrength = 255,
                    canSign = false,
                    canEncrypt = true,
                    revoked = false,
                    createdAt = null,
                    expiresAt = null,
                ),
            ),
        ),
        hasSigningPrivateMaterial = true,
        hasDecryptionPrivateMaterial = true,
        privateKeyArmored = "private",
        now = Instant.fromEpochSeconds(0),
    )

    private companion object {
        const val PRIMARY_FINGERPRINT = "A123456789ABCDEF0123456789ABCDEF01234567"
        const val SUBKEY_FINGERPRINT = "B123456789ABCDEF0123456789ABCDEF01234567"
        const val PRIMARY_KEYGRIP = "1111111111111111111111111111111111111111"
        const val SUBKEY_KEYGRIP = "2222222222222222222222222222222222222222"
    }
}
