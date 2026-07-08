package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.crypto.GpgKeygripCalculatorJvm
import com.artemchep.keyguard.crypto.fingerprintHex
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import java.io.ByteArrayInputStream
import java.security.Security
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-value tests for [GpgKeygripCalculatorJvm].
 *
 * A keygrip is GnuPG's stable, algorithm-specific SHA-1 identifier of a public key; the
 * gpg-agent addresses every private-key operation by keygrip. If Keyguard's keygrip does
 * not byte-match the one gpg derives from the same key, gpg asks the agent for a keygrip
 * it has never heard of and the operation fails with KEY_NOT_FOUND. These fixtures are
 * exactly the keys that `gpg --with-keygrip` (gpg 2.5.20 / libgcrypt 1.12) was run
 * against, so the expected values below are ground truth pulled straight from gpg.
 *
 * Every public key in each ring is checked: both the primary and its subkey. These tests
 * are expected to FAIL against the current implementation, which computes the RSA/ECC
 * keygrips over the wrong byte sequence (and throws for the ECDSA/ECDH algorithm ids 18
 * and 19 it does not handle at all) — the fixes land in a follow-up task.
 */
class GpgKeygripCalculatorJvmTest {
    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `rsa keygrips match gpg`() {
        assertKeygrips(
            armored = GpgTestKeyFixtures.RSA,
            golden = mapOf(
                "C012A9933D4FAB8F5CEE629A110819322AF4BB33" to "FD8D39587B8922D46A79AAADC471EE2307694290",
                "A05381E7CE6A5EF990D8A4D6D220055EC1BDD0E3" to "F9FB47B4DAA81B0303FCA644114A5E6F5242CE00",
            ),
        )
    }

    @Test
    fun `ed25519 keygrips match gpg`() {
        assertKeygrips(
            armored = GpgTestKeyFixtures.ED25519,
            golden = mapOf(
                "0CE41BC6784E7D400FE9ED43BD6737048F2BF18F" to "229970A02F1B36D69474047D2AC133D6DA3AB204",
            ),
        )
    }

    @Test
    fun `cv25519 keygrips match gpg`() {
        assertKeygrips(
            armored = GpgTestKeyFixtures.CV25519,
            golden = mapOf(
                "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7" to "894264A490F8D55E3E28378A7E44373782806220",
                "93ABCF804D85EE79D6E1DB0E77648D3E5D4E7699" to "85C1DE785BEE9244BAFBA73A09E6085BA7A35C8E",
            ),
        )
    }

    @Test
    fun `nistp256 keygrips match gpg`() {
        assertKeygrips(
            armored = GpgTestKeyFixtures.NISTP256,
            golden = mapOf(
                "D79697513594B09CBFBEAA4E3966D0BA233255B6" to "EA256F3A98512E4BAB35BB0E99BC259DD2C1A242",
                "9795F5175E07494C4834F5A9A1237267B46FFABF" to "D9296C0DE59884DDD4A8712BE13FE6C93925F364",
            ),
        )
    }

    @Test
    fun `ecdsa keygrips match gpg`() {
        assertKeygrips(
            armored = GpgTestKeyFixtures.ECDSA,
            golden = mapOf(
                "0BBD0A44ED8693CF1509FE24C1E64DF5055BB9B7" to "9701E87A2B0B73F6EC571C8152D2BB667C9E1426",
            ),
        )
    }

    private fun assertKeygrips(
        armored: String,
        golden: Map<String, String>,
    ) {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        val publicKeys = collection.keyRings.asSequence()
            .flatMap { it.publicKeys.asSequence() }
            .toList()
        // Sanity-check the fixture wiring: every public key must have a golden keygrip.
        assertEquals(
            golden.keys,
            publicKeys.map { it.fingerprintHex() }.toSet(),
            "golden table must cover exactly the keys in the ring",
        )
        publicKeys.forEach { publicKey ->
            val fingerprint = publicKey.fingerprintHex()
            val expected = golden.getValue(fingerprint)
            assertEquals(
                expected,
                GpgKeygripCalculatorJvm.calculate(publicKey),
                "keygrip mismatch for $fingerprint",
            )
        }
    }
}
