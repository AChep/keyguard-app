package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class GpgKeyUsabilityTest {
    @Test
    fun `a key without an expiry never expires`() {
        val key = primary(expiresAt = null)
        assertFalse(key.isExpiredAt(NOW))
        assertTrue(key.isActiveAt(NOW))
    }

    @Test
    fun `expiry exactly at the evaluation instant counts as expired`() {
        assertTrue(primary(expiresAt = NOW).isExpiredAt(NOW))
        assertFalse(primary(expiresAt = NOW.plus(ONE_SECOND)).isExpiredAt(NOW))
        assertTrue(subKey(expiresAt = NOW).isExpiredAt(NOW))
        assertFalse(subKey(expiresAt = NOW.plus(ONE_SECOND)).isExpiredAt(NOW))
    }

    @Test
    fun `a revoked or expired primary disables the whole certificate`() {
        val revoked = primary(revoked = true).copy(
            subKeys = listOf(subKey(canSign = true, canEncrypt = true)),
        )
        assertFalse(revoked.canSignAt(NOW))
        assertFalse(revoked.canEncryptAt(NOW))

        val expired = primary(expiresAt = NOW.minus(ONE_SECOND)).copy(
            subKeys = listOf(subKey(canSign = true, canEncrypt = true)),
        )
        assertFalse(expired.canSignAt(NOW))
        assertFalse(expired.canEncryptAt(NOW))
    }

    @Test
    fun `a capable primary is usable on its own`() {
        val key = primary(canSign = true, canEncrypt = true)
        assertTrue(key.canSignAt(NOW))
        assertTrue(key.canEncryptAt(NOW))
    }

    @Test
    fun `a certify-only primary is usable through an active subkey`() {
        val key = primary().copy(
            subKeys = listOf(subKey(canSign = true, canEncrypt = true)),
        )
        assertTrue(key.canSignAt(NOW))
        assertTrue(key.canEncryptAt(NOW))
    }

    @Test
    fun `a certify-only primary is unusable when every capable subkey is dead`() {
        val key = primary().copy(
            subKeys = listOf(
                subKey(canSign = true, canEncrypt = true, revoked = true),
                subKey(canSign = true, canEncrypt = true, expiresAt = NOW),
            ),
        )
        assertFalse(key.canSignAt(NOW))
        assertFalse(key.canEncryptAt(NOW))
    }

    @Test
    fun `a capable primary stays usable when its capable subkeys are dead`() {
        // A revoked subkey retires that subkey, not the primary's own
        // capability: the certificate can still sign with the primary.
        val key = primary(canSign = true, canEncrypt = true).copy(
            subKeys = listOf(
                subKey(canSign = true, canEncrypt = true, revoked = true),
            ),
        )
        assertTrue(key.canSignAt(NOW))
        assertTrue(key.canEncryptAt(NOW))
    }

    @Test
    fun `capabilities are evaluated independently`() {
        val signOnly = primary(canSign = true)
        assertTrue(signOnly.canSignAt(NOW))
        assertFalse(signOnly.canEncryptAt(NOW))

        val encryptOnlySubKey = primary().copy(
            subKeys = listOf(subKey(canEncrypt = true)),
        )
        assertFalse(encryptOnlySubKey.canSignAt(NOW))
        assertTrue(encryptOnlySubKey.canEncryptAt(NOW))
    }

    private companion object {
        val NOW = Instant.fromEpochSeconds(1_700_000_000)
        val ONE_SECOND = kotlin.time.Duration.parse("1s")

        fun primary(
            canSign: Boolean = false,
            canEncrypt: Boolean = false,
            revoked: Boolean = false,
            expiresAt: Instant? = null,
        ) = GpgPublicKeyInfo(
            fingerprint = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
            keyId = "F83D947D29EFECF7",
            algorithm = "EDDSA",
            bitStrength = 256,
            userIds = listOf("Keyguard Test <test@test.invalid>"),
            emails = listOf("test@test.invalid"),
            createdAt = null,
            expiresAt = expiresAt,
            revoked = revoked,
            canSign = canSign,
            canEncrypt = canEncrypt,
            publicKeyArmored = "",
            subKeys = emptyList(),
        )

        fun subKey(
            canSign: Boolean = false,
            canEncrypt: Boolean = false,
            revoked: Boolean = false,
            expiresAt: Instant? = null,
        ) = GpgPublicSubKeyInfo(
            fingerprint = "93ABCF804D85EE79D6E1DB0E77648D3E5D4E7699",
            keyId = "77648D3E5D4E7699",
            algorithm = "ECDH",
            canSign = canSign,
            canEncrypt = canEncrypt,
            revoked = revoked,
            expiresAt = expiresAt,
        )
    }
}
