package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCryptoPrimitivesInputTest {
    @Test
    fun selectsHmacTransportAtTheMeasuredCrossoverWithoutOverflow() {
        assertTrue(
            shouldUseOneShotHmac(
                keySize = 32,
                dataSize = NATIVE_CRYPTO_HMAC_ONE_SHOT_MAX_BYTES - 32,
            ),
        )
        assertFalse(
            shouldUseOneShotHmac(
                keySize = 32,
                dataSize = NATIVE_CRYPTO_HMAC_ONE_SHOT_MAX_BYTES - 31,
            ),
        )
        assertFalse(shouldUseOneShotHmac(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun rejectsSignedValuesBeforeEncodingUnsignedProtocolFields() {
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.hkdfSha256(ByteArray(0), length = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.pbkdf2Sha256(ByteArray(0), ByteArray(0), iterations = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.pbkdf2Sha256(ByteArray(0), ByteArray(0), length = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.argon2(
                mode = NativeArgon2Mode.ARGON2_ID,
                seed = ByteArray(0),
                salt = ByteArray(8),
                iterations = -1,
                memoryKb = 8,
                parallelism = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.argon2(
                mode = NativeArgon2Mode.ARGON2_ID,
                seed = ByteArray(0),
                salt = ByteArray(8),
                iterations = 1,
                memoryKb = -1,
                parallelism = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.argon2(
                mode = NativeArgon2Mode.ARGON2_ID,
                seed = ByteArray(0),
                salt = ByteArray(8),
                iterations = 1,
                memoryKb = 8,
                parallelism = 1,
                length = 3,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.randomBytes(-1)
        }
    }

    @Test
    fun acceptsEveryPositiveKotlinIntPbkdf2WorkFactor() {
        val result = NativeCryptoPrimitives.pbkdf2Sha256(
            seed = ByteArray(0),
            salt = ByteArray(0),
            iterations = Int.MAX_VALUE,
            length = 0,
        )

        assertEquals(0, result.size)
    }

    @Test
    fun rejectsInvalidKdbxCipherParametersBeforeCallingNativeCode() {
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.streamCipherXorAtOffset(
                algorithm = NativeStreamCipherAlgorithm.SALSA20,
                key = ByteArray(31),
                nonce = ByteArray(8),
                offset = 0,
                data = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.streamCipherXorAtOffset(
                algorithm = NativeStreamCipherAlgorithm.CHACHA20,
                key = ByteArray(32),
                nonce = ByteArray(8),
                offset = 0,
                data = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.streamCipherXorAtOffset(
                algorithm = NativeStreamCipherAlgorithm.SALSA20,
                key = ByteArray(32),
                nonce = ByteArray(8),
                offset = -1,
                data = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.streamCipherXorAtOffset(
                algorithm = NativeStreamCipherAlgorithm.SALSA20,
                key = ByteArray(32),
                nonce = ByteArray(8),
                offset = Long.MAX_VALUE,
                data = ByteArray(1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.twofishCbcPkcs7Encrypt(
                key = ByteArray(15),
                iv = ByteArray(16),
                data = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.twofishCbcPkcs7Encrypt(
                key = ByteArray(16),
                iv = ByteArray(15),
                data = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.twofishCbcPkcs7Decrypt(
                key = ByteArray(16),
                iv = ByteArray(16),
                data = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.twofishCbcPkcs7Decrypt(
                key = ByteArray(16),
                iv = ByteArray(16),
                data = ByteArray(15),
            )
        }
    }

    @Test
    fun rejectsInvalidParametersBeforeCallingNativeCode() {
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.createAesCbcPkcs7Encryptor(
                key = ByteArray(15),
                iv = ByteArray(16),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.createAesCbcPkcs7Decryptor(
                key = ByteArray(32),
                iv = ByteArray(15),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.rsaOaepEncrypt(
                publicKeySpki = ByteArray(0),
                plaintext = ByteArray(0),
                hash = NativeRsaOaepHash.SHA_256,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.rsaOaepDecrypt(
                privateKeyPkcs8 = ByteArray(0),
                ciphertext = ByteArray(0),
                hash = NativeRsaOaepHash.SHA_1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.rsaPublicKeySpkiFromPkcs8(ByteArray(0))
        }
    }

    @Test
    fun rejectsInvalidSshAgentTransportParametersBeforeCallingNativeCode() {
        val key = ByteArray(32)
        val nonce = ByteArray(12)
        val header = ByteArray(18)

        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Encrypt(
                key = ByteArray(31),
                nonce = nonce,
                header = header,
                payload = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Encrypt(
                key = key,
                nonce = ByteArray(11),
                header = header,
                payload = ByteArray(0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Encrypt(
                key = key,
                nonce = nonce,
                header = ByteArray(17),
                payload = ByteArray(0),
            )
        }
        val encryptLimitError = assertFailsWith<NativeCryptoException> {
            NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Encrypt(
                key = key,
                nonce = nonce,
                header = header,
                payload = ByteArray(1024 * 1024 + 1),
            )
        }
        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, encryptLimitError.code)
        assertFailsWith<IllegalArgumentException> {
            NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Decrypt(
                key = key,
                nonce = nonce,
                header = header,
                payload = ByteArray(15),
            )
        }
        val decryptLimitError = assertFailsWith<NativeCryptoException> {
            NativeCryptoPrimitives.sshAgentTcpChaCha20Poly1305Decrypt(
                key = key,
                nonce = nonce,
                header = header,
                payload = ByteArray(1024 * 1024 + 17),
            )
        }
        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, decryptLimitError.code)
    }

    @Test
    fun rejectsInvalidSshKeyParametersBeforeCallingNativeCode() {
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.ssh.generate(NativeSshKeyType.RSA, rsaBits = 1536)
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.ssh.generate(NativeSshKeyType.ED25519, rsaBits = 2048)
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.ssh.parse(privateKeyPem = "", publicKeyOpenSsh = "ssh-rsa value")
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.ssh.describe(
                type = NativeSshKeyType.RSA,
                privateKey = ByteArray(0),
                publicKey = byteArrayOf(1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.ssh.sign(
                privateKeyPem = "key",
                publicKeyOpenSsh = null,
                data = ByteArray(1024 * 1024 + 1),
                flags = 0,
            )
        }
    }

    @Test
    fun rejectsNegativeOpenPgpReferenceTimesBeforeCallingNativeCode() {
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.parsePublicKeys(
                keyData = byteArrayOf(1),
                referenceTimeEpochSeconds = -1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.verifyClearSigned(
                signedDocument = byteArrayOf(1),
                publicKeys = emptyList(),
                referenceTimeEpochSeconds = -1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.verifyDetached(
                content = byteArrayOf(1),
                signature = byteArrayOf(2),
                publicKeys = emptyList(),
                referenceTimeEpochSeconds = -1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.openDetachedVerification(
                signature = byteArrayOf(1),
                publicKeys = emptyList(),
                referenceTimeEpochSeconds = -1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.resolveMetadata(
                privateKeyData = null,
                publicKeyData = byteArrayOf(1),
                referenceTimeEpochSeconds = -1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.evaluateUserIdCertifications(
                publicKey = byteArrayOf(1),
                authorities = emptyList(),
                referenceTimeEpochSeconds = -1L,
            )
        }
    }

    @Test
    fun rejectsInvalidOpenPgpCertificationAuthorityInputsBeforeCallingNativeCode() {
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.evaluateUserIdCertifications(
                publicKey = byteArrayOf(),
                authorities = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.evaluateUserIdCertifications(
                publicKey = byteArrayOf(1),
                authorities = listOf(
                    NativeOpenPgpCertificationAuthority(
                        publicKey = byteArrayOf(),
                        primaryFingerprint = "A".repeat(40),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.evaluateUserIdCertifications(
                publicKey = byteArrayOf(1),
                authorities = listOf(
                    NativeOpenPgpCertificationAuthority(
                        publicKey = byteArrayOf(2),
                        primaryFingerprint = "not-a-fingerprint",
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NativeCrypto.openPgp.evaluateUserIdCertifications(
                publicKey = byteArrayOf(1),
                authorities = List(NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST) {
                    NativeOpenPgpCertificationAuthority(
                        publicKey = byteArrayOf(2),
                        primaryFingerprint = "A".repeat(40),
                    )
                },
            )
        }
    }
}
