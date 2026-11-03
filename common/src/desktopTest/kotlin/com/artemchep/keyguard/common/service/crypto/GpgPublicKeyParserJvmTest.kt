package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.crypto.armored
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [NativeGpgPublicKeyParser], the native Rust parser used to read ASCII-armored
 * public keys fetched from keyservers. Bouncy Castle remains here only as a differential
 * fixture decoder/re-armorer.
 *
 * The fixture is a real, unprotected Ed25519 cert primary with a CV25519
 * encryption subkey, the public half of which is derived in-test and re-armored
 * exactly the way a keyserver would serve it.
 */
class GpgPublicKeyParserJvmTest {
    private val parser = NativeGpgPublicKeyParser

    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `parses fingerprint, e-mail and capabilities from an armored public key`() {
        val armored = publicKeyArmoredOf(GpgTestKeyFixtures.CV25519)

        val result = parser.parse(armored)

        assertTrue(result is GpgPublicKeyParseResult.Success, "expected Success, got $result")
        val key = result.keys.single()
        assertEquals(
            "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
            key.fingerprint,
        )
        assertTrue(
            "cv25519@test.invalid" in key.emails,
            "expected the verified e-mail to be extracted, got ${key.emails}",
        )
        // The primary is a signing key; the ring carries a CV25519 encryption
        // subkey.
        assertTrue(key.canSign, "primary should be sign-capable")
        assertTrue(key.canEncrypt, "ring has an encryption subkey")
        assertEquals(40, key.keygrip?.length)
        assertTrue(key.subKeys.any { it.keygrip?.length == 40 }, "subkey should carry a keygrip")
        assertTrue(key.subKeys.any { it.canEncrypt }, "subkey should be encryption-capable")
        assertEquals(false, key.revoked)
    }

    @Test
    fun `blank input returns Empty`() {
        val result = parser.parse("   ")
        assertEquals(
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Empty),
            result,
        )
    }

    @Test
    fun `garbage input returns Malformed`() {
        val result = parser.parse("this is not a pgp key")
        assertEquals(
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed),
            result,
        )
    }

    @Test
    fun `legacy V2 and V3 public keys are unsupported`() {
        GpgLegacyKeyFixtures.versions.forEach { version ->
            val result = parser.parse(
                GpgLegacyKeyFixtures.publicRing(version).armored(),
            )
            assertEquals(
                GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.UnsupportedKeyVersion),
                result,
            )
        }
    }

    @Test
    fun `primary key lookup requires a supplied fingerprint to match`() {
        val armored = publicKeyArmoredOf(GpgTestKeyFixtures.CV25519)

        assertNull(
            parser.parsePrimaryKeyInfo(
                armored = armored,
                fingerprint = "0000000000000000000000000000000000000000",
            ),
        )
        assertNotNull(
            parser.parsePrimaryKeyInfo(
                armored = armored,
                fingerprint = "d0bb cfbb 250d 3bb0 658e 5384 f83d 947d 29ef ecf7",
            ),
        )
        assertNotNull(
            parser.parsePrimaryKeyInfo(
                armored = armored,
                fingerprint = "",
            ),
        )
        assertNotNull(
            parser.parsePrimaryKeyInfo(
                armored = armored,
                fingerprint = null,
            ),
        )
        assertNotNull(parser.parsePrimaryKeyInfo(armored = armored))
    }
}
