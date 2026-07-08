package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.crypto.GpgPublicKeyParserJvm
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.Security
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [GpgPublicKeyParserJvm], the BouncyCastle-backed parser used to read
 * ASCII-armored public keys fetched from keyservers.
 *
 * The fixture is a real, unprotected Ed25519 cert primary with a CV25519
 * encryption subkey, the public half of which is derived in-test and re-armored
 * exactly the way a keyserver would serve it.
 */
class GpgPublicKeyParserJvmTest {
    private val parser = GpgPublicKeyParserJvm()

    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `parses fingerprint, e-mail and capabilities from an armored public key`() {
        val armored = publicKeyArmoredOf(CV25519_SECRET_KEY)

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

    private fun publicKeyArmoredOf(
        secretArmored: String,
    ): String {
        val secretCollection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(secretArmored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        val secretRing = secretCollection.keyRings.next()
        val publicRing = PGPPublicKeyRing(
            secretRing.publicKeys.asSequence().toList(),
        )
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armoredOut ->
            publicRing.encode(armoredOut)
        }
        return out.toString(Charsets.UTF_8)
    }

    companion object {
        // Real, unprotected (empty-passphrase) Ed25519 cert primary with a
        // CV25519 (algorithm 18, ECDH) encryption subkey. User-id:
        // "Keyguard Test CV25519 <cv25519@test.invalid>".
        private val CV25519_SECRET_KEY = """
            -----BEGIN PGP PRIVATE KEY BLOCK-----

            lFgEaj9rzxYJKwYBBAHaRw8BAQdAbF/WEPrIP6KKXMDvdC38qJefWOzgPjl1oRjO
            Zq0b1Q4AAP416BYYjfvazxmhBWie0YPQHmRv5DtZABE+5Eo8vsGC8BB2tCxLZXln
            dWFyZCBUZXN0IENWMjU1MTkgPGN2MjU1MTlAdGVzdC5pbnZhbGlkPoivBBMWCgBX
            FiEE0LvPuyUNO7BljlOE+D2UfSnv7PcFAmo/a88bFIAAAAAABAAObWFudTIsMi41
            KzEuMTIsMCwzAhsDBQsJCAcCAiICBhUKCQgLAgQWAgMBAh4HAheAAAoJEPg9lH0p
            7+z3szkA/iTKzuwQ/a33NXIiGaEluTQsPTfvLZFPHzsSrHRUPtAxAP4me3t1tgkV
            BrbfFEx8MwS2TpYJ+TseDv+Pf+vwp/doBJxdBGo/a+wSCisGAQQBl1UBBQEBB0Bc
            0xWVtzx07/KrLcmPAncTB+02SZ5KSLrZ4UXO8bp9dgMBCAcAAP934N+JD9z0Gkm1
            ZSVtLdTx8gIrDriwen2vkSJLUzL+UBCqiJQEGBYKADwWIQTQu8+7JQ07sGWOU4T4
            PZR9Ke/s9wUCaj9r7BsUgAAAAAAEAA5tYW51MiwyLjUrMS4xMiwwLDMCGwwACgkQ
            +D2UfSnv7PculAD/T22Upu3v6Pbqn5DBsKxu7yiu4LFs1jjnbbp7LLpDFL0BALpz
            Bc+fU17BLteMYYp5rXgKCOm+qy1Z70+LJ8ljtz4I
            =s3tp
            -----END PGP PRIVATE KEY BLOCK-----
        """.trimIndent()
    }
}
