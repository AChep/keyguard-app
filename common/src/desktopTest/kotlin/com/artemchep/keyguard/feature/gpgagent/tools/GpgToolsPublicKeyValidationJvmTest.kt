package com.artemchep.keyguard.feature.gpgagent.tools

import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgTestKeyFixtures
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Exercises the packet-preserving parser contract with real GnuPG certificates. */
class GpgToolsPublicKeyValidationJvmTest {
    @Test
    fun `real public certificate is accepted as an encryption recipient`() {
        val key = publicKey(GpgTestKeyFixtures.RSA)
        val result = validateGpgToolsPublicKeys(key, NativeGpgPublicKeyParser, forEncryption = true)
        assertEquals(key, assertIs<GpgToolsPublicKeyValidationResult.Success>(result).keys.single().publicKeyArmored)
    }

    @Test
    fun `multiple real certificates are shown and duplicates are removed`() {
        val first = publicKey(GpgTestKeyFixtures.RSA)
        val second = publicKey(GpgTestKeyFixtures.ED25519)
        val result = validateGpgToolsPublicKeys("$first\n$second\n$first", NativeGpgPublicKeyParser, false)
        assertEquals(2, assertIs<GpgToolsPublicKeyValidationResult.Success>(result).keys.size)
    }

    @Test
    fun `real secret certificates cannot be disguised with a public armor label`() {
        for (secret in listOf(GpgTestKeyFixtures.RSA, GpgTestKeyFixtures.ED25519)) {
            assertIs<GpgToolsPublicKeyValidationResult.Error>(
                validateGpgToolsPublicKeys(
                    secret.replace("PRIVATE KEY BLOCK", "PUBLIC KEY BLOCK"),
                    NativeGpgPublicKeyParser,
                    false,
                ),
            )
        }
    }

    @Test
    fun `a signing only certificate remains usable for verification but not encryption`() {
        val key = publicKey(GpgTestKeyFixtures.ED25519)
        assertIs<GpgToolsPublicKeyValidationResult.Success>(
            validateGpgToolsPublicKeys(key, NativeGpgPublicKeyParser, false),
        )
        assertIs<GpgToolsPublicKeyValidationResult.Error>(
            validateGpgToolsPublicKeys(key, NativeGpgPublicKeyParser, true),
        )
    }

    private fun publicKey(secret: String): String =
        assertIs<GpgPublicKeyParseResult.Success>(NativeGpgPublicKeyParser.parse(secret))
            .keys.single().publicKeyArmored
}
