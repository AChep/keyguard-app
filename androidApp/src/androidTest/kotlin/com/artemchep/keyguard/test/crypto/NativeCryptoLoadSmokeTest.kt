package com.artemchep.keyguard.test.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPublicKeyParseResult
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpVerificationStatus
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import com.artemchep.keyguard.nativecrypto.NativeSshPrivateKeyImportError
import com.artemchep.keyguard.nativecrypto.NativeSshPrivateKeyImportResult
import com.artemchep.keyguard.util.foundation.crypto.PlatformCryptoPrimitives
import com.artemchep.keyguard.util.foundation.crypto.ensurePlatformCryptoReady
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class NativeCryptoLoadSmokeTest {
    @Test
    fun loadsNativeLibraryAndExecutesSmokeCoverage() {
        ensurePlatformCryptoReady()

        val actual = PlatformCryptoPrimitives().sha256("abc".encodeToByteArray())

        assertArrayEquals(SHA256_ABC, actual)
        val key = ByteArray(32) { index -> index.toByte() }
        val ciphertext = NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Encrypt(
            key = key,
            nonce = SSH_AGENT_NONCE,
            header = SSH_AGENT_HEADER,
            payload = SSH_AGENT_PLAINTEXT,
        )
        assertArrayEquals(SSH_AGENT_CIPHERTEXT, ciphertext)
        assertArrayEquals(
            SSH_AGENT_PLAINTEXT,
            NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Decrypt(
                key = key,
                nonce = SSH_AGENT_NONCE,
                header = SSH_AGENT_HEADER,
                payload = ciphertext,
            ),
        )
        val sshKey = NativeCrypto.ssh.generate(NativeSshKeyType.ED25519)
        var signature: ByteArray? = null
        try {
            val description = NativeCrypto.ssh.describe(
                type = sshKey.type,
                privateKey = sshKey.privateKey,
                publicKey = sshKey.publicKey,
            )
            val signed = NativeCrypto.ssh.sign(
                privateKeyPem = description.privateKeyPem,
                publicKeyOpenSsh = description.publicKeyOpenSsh,
                data = "native-crypto-android-smoke".encodeToByteArray(),
                flags = 0,
            )
            signature = signed.signature
            assertEquals("ssh-ed25519", signed.algorithm)
            assertEquals(64, signed.signature.size)
        } finally {
            sshKey.privateKey.fill(0)
            signature?.fill(0)
        }
        val imported = NativeCrypto.ssh.importPrivateKey(OPENSSH_ED25519)
        val importedKey = (imported as NativeSshPrivateKeyImportResult.Success).keyMaterial
        try {
            assertEquals(NativeSshKeyType.ED25519, importedKey.type)
        } finally {
            importedKey.privateKey.fill(0)
        }
        assertEncryptedSshImport(OPENSSH_ED25519_AES256_GCM)
        assertEncryptedSshImport(OPENSSH_ED25519_CHACHA20_POLY1305)
        assertOpenPgpReadPath()
    }

    private fun assertOpenPgpReadPath() {
        val publicKey = OPENPGP_PUBLIC_KEY.encodeToByteArray()
        val parsed = NativeCrypto.openPgp.parsePublicKeys(
            keyData = publicKey,
            referenceTimeEpochSeconds = OPENPGP_REFERENCE_TIME,
        ) as NativeOpenPgpPublicKeyParseResult.Success
        assertEquals(OPENPGP_FINGERPRINT, parsed.keys.single().fingerprint)

        val verification = NativeCrypto.openPgp.verifyDetached(
            content = OPENPGP_DETACHED_BODY.encodeToByteArray(),
            signature = OPENPGP_DETACHED_SIGNATURE.encodeToByteArray(),
            publicKeys = listOf(publicKey),
            referenceTimeEpochSeconds = OPENPGP_REFERENCE_TIME,
        )
        assertEquals(NativeOpenPgpVerificationStatus.VALID, verification.status)
        assertEquals(OPENPGP_FINGERPRINT, verification.fingerprint)
    }

    private fun assertEncryptedSshImport(content: String) {
        val imported = NativeCrypto.ssh.importPrivateKey(
            content = content,
            passphrase = OPENSSH_AEAD_PASSPHRASE,
        ) as NativeSshPrivateKeyImportResult.Success
        try {
            assertEquals(NativeSshKeyType.ED25519, imported.keyMaterial.type)
            assertArrayEquals(EXPECTED_ED25519_PUBLIC_KEY, imported.keyMaterial.publicKey)
        } finally {
            imported.keyMaterial.privateKey.fill(0)
        }

        assertEquals(
            NativeSshPrivateKeyImportResult.Error(
                NativeSshPrivateKeyImportError.INVALID_PASSPHRASE,
            ),
            NativeCrypto.ssh.importPrivateKey(
                content = content,
                passphrase = "wrong-passphrase",
            ),
        )
    }

    private companion object {
        val SHA256_ABC = byteArrayOf(
            0xba.toByte(),
            0x78,
            0x16,
            0xbf.toByte(),
            0x8f.toByte(),
            0x01,
            0xcf.toByte(),
            0xea.toByte(),
            0x41,
            0x41,
            0x40,
            0xde.toByte(),
            0x5d,
            0xae.toByte(),
            0x22,
            0x23,
            0xb0.toByte(),
            0x03,
            0x61,
            0xa3.toByte(),
            0x96.toByte(),
            0x17,
            0x7a,
            0x9c.toByte(),
            0xb4.toByte(),
            0x10,
            0xff.toByte(),
            0x61,
            0xf2.toByte(),
            0x00,
            0x15,
            0xad.toByte(),
        )
        val SSH_AGENT_NONCE = hex("a0a1a2a30000000000000001")
        val SSH_AGENT_HEADER = hex("4b5341470203000000000000000100000028")
        val SSH_AGENT_PLAINTEXT = "keyguard-ssh-agent-frame".encodeToByteArray()
        val SSH_AGENT_CIPHERTEXT = hex(
            "4cb94ca92fd4281424e0b87c31a8a7cbabb723966ade916ef50ed0595bcf22b4" +
                "b63cd9fd80bc498b",
        )
        val EXPECTED_ED25519_PUBLIC_KEY = hex(
            "0000000b7373682d6564323535313900000020" +
                "b33eaef37ea2df7caa010defdea34e241f65f1b529a4f43ed14327f5c54aab62",
        )
        const val OPENSSH_AEAD_PASSPHRASE = "hunter42"
        const val OPENPGP_REFERENCE_TIME = 1_783_944_100L
        const val OPENPGP_FINGERPRINT = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7"
        val OPENPGP_DETACHED_BODY = """
            Independent OpenPGP verification fixture.
            Second line.
        """.trimIndent() + "\n"
        val OPENPGP_PUBLIC_KEY = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----

            mDMEaj9rzxYJKwYBBAHaRw8BAQdAbF/WEPrIP6KKXMDvdC38qJefWOzgPjl1oRjO
            Zq0b1Q60LEtleWd1YXJkIFRlc3QgQ1YyNTUxOSA8Y3YyNTUxOUB0ZXN0LmludmFs
            aWQ+iK8EExYKAFcWIQTQu8+7JQ07sGWOU4T4PZR9Ke/s9wUCaj9rzxsUgAAAAAAE
            AA5tYW51MiwyLjUrMS4xMiwwLDMCGwMFCwkIBwICIgIGFQoJCAsCBBYCAwECHgcC
            F4AACgkQ+D2UfSnv7PezOQD+JMrO7BD9rfc1ciIZoSW5NCw9N+8tkU8fOxKsdFQ+
            0DEA/iZ7e3W2CRUGtt8UTHwzBLZOlgn5Ox4O/49/6/Cn92gEuDgEaj9r7BIKKwYB
            BAGXVQEFAQEHQFzTFZW3PHTv8qstyY8CdxMH7TZJnkpIutnhRc7xun12AwEIB4iU
            BBgWCgA8FiEE0LvPuyUNO7BljlOE+D2UfSnv7PcFAmo/a+wbFIAAAAAABAAObWFu
            dTIsMi41KzEuMTIsMCwzAhsMAAoJEPg9lH0p7+z3LpQA/09tlKbt7+j26p+QwbCs
            bu8oruCxbNY45226eyy6QxS9AQC6cwXPn1NewS7XjGGKea14CgjpvqstWe9PiyfJ
            Y7c+CA==
            =Kf2G
            -----END PGP PUBLIC KEY BLOCK-----
        """.trimIndent() + "\n"
        val OPENPGP_DETACHED_SIGNATURE = """
            -----BEGIN PGP SIGNATURE-----

            iJEEABYKADkWIQTQu8+7JQ07sGWOU4T4PZR9Ke/s9wUCalbNgBsUgAAAAAAEAA5t
            YW51MiwyLjUrMS4xMiwwLDMACgkQ+D2UfSnv7Pe4sQEAowtp7N4njm4eBEi+bgC1
            VxGYWoE70RB//wCTrwaVtggBAL3MVySwcv/iU0y9pM+91TaerHhzhSNnDjcJTS4d
            SOEL
            =6B1K
            -----END PGP SIGNATURE-----
        """.trimIndent() + "\n"
        const val OPENSSH_ED25519 = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYgAAAJgAIAxdACAM
XQAAAAtzc2gtZWQyNTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYg
AAAEC2BsIi0QwW2uFscKTUUXNHLsYX4FxlaSDSblbAj7WR7bM+rvN+ot98qgEN796jTiQf
ZfG1KaT0PtFDJ/XFSqtiAAAAEHVzZXJAZXhhbXBsZS5jb20BAgMEBQ==
-----END OPENSSH PRIVATE KEY-----"""

        const val OPENSSH_ED25519_AES256_GCM = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAAFmFlczI1Ni1nY21Ab3BlbnNzaC5jb20AAAAGYmNyeXB0AA
AAGAAAABARvcEz72RkQRWxdpF+R8uvAAAAEAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAA
ILM+rvN+ot98qgEN796jTiQfZfG1KaT0PtFDJ/XFSqtiAAAAoIJQm81qpEdHOG7cGK5d27
FAelmbS6xxp7YaqYnD+9agVk6KsbAM8SMDF6AEiVaxoVPX/+HRV1HwA5BRpWijXmC6meyV
604UAY1ubJKemubnSrNSa4slV/r6wLut1vqFD8ro6nobT+wCgUrwDsL7ZI/9i6nQYXFdDS
vKbSu+2Nwh3B78JQoZXyetXQy3fOZKqrvy/6BFRDsOTKckfRCiAaTcNzfq+DH3OG5x+brH
Yl4J
-----END OPENSSH PRIVATE KEY-----"""

        const val OPENSSH_ED25519_CHACHA20_POLY1305 = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAAHWNoYWNoYTIwLXBvbHkxMzA1QG9wZW5zc2guY29tAAAABm
JjcnlwdAAAABgAAAAQ9lHKPvsVkE0FwhalBB6omgAAABAAAAABAAAAMwAAAAtzc2gtZWQy
NTUxOQAAACCzPq7zfqLffKoBDe/eo04kH2XxtSmk9D7RQyf1xUqrYgAAAJiRvYDd00XU/W
BkZ93ZW52HNwvM2m3z/MHuqD8q/tk16rKKtBNOc95wo4gyRzkdGYhKnF1RFCJYcdvlw6zo
kctfmmhQ6W54G6u9Eh9bIJtHt3l4FQgzriuIsBTUKZIlvvk6Fo5ItNPHM00r2ehuX81lcZ
QHMaims6Blw8Esl6G3NYCAa2NKyqlmM5LIfkga/Ymydvrbc7EQmN2hbii0c0aMUdYQclyk
F4o=
-----END OPENSSH PRIVATE KEY-----"""

        private fun hex(value: String): ByteArray = value
            .chunked(2)
            .map { byte -> byte.toInt(16).toByte() }
            .toByteArray()
    }
}
