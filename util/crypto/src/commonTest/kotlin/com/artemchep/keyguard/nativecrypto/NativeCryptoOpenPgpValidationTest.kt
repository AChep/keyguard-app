@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeCryptoOpenPgpValidationTest {
    @Test
    fun rejectsInvalidKeyGenerationInputsBeforeLoadingNativeCode() {
        assertInvalidInput {
            NativeCrypto.openPgp.generateKey(
                kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
                userId = "   ",
                creationTimeEpochSeconds = 0L,
            )
        }
        assertInvalidInput {
            NativeCrypto.openPgp.generateKey(
                kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
                userId = "Alice",
                creationTimeEpochSeconds = -1L,
            )
        }
        for (expiration in listOf(0L, UInt.MAX_VALUE.toLong() + 1L)) {
            assertInvalidInput {
                NativeCrypto.openPgp.generateKey(
                    kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
                    userId = "Alice",
                    creationTimeEpochSeconds = 0L,
                    expirationSeconds = expiration,
                )
            }
        }
        assertInvalidInput {
            NativeCrypto.openPgp.generateKey(
                kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
                userId = "Alice",
                rsaBits = 3_072,
                creationTimeEpochSeconds = 0L,
            )
        }
        for (rsaBits in listOf(0, 1_024, 2_048, 8_192)) {
            assertInvalidInput {
                NativeCrypto.openPgp.generateKey(
                    kind = NativeOpenPgpKeyKind.RSA,
                    userId = "Alice",
                    rsaBits = rsaBits,
                    creationTimeEpochSeconds = 0L,
                )
            }
        }
    }

    @Test
    fun rejectsInvalidSigningInputsBeforeLoadingNativeCode() {
        assertInvalidInput {
            NativeCrypto.openPgp.clearSign(
                content = byteArrayOf(1),
                privateKey = byteArrayOf(),
            )
        }
        for (
            fingerprint in listOf(
                "A".repeat(30),
                "A".repeat(33),
                "a".repeat(40),
                "G".repeat(40),
                "A".repeat(130),
            )
        ) {
            assertInvalidInput {
                NativeCrypto.openPgp.signDetached(
                    content = byteArrayOf(1),
                    privateKey = byteArrayOf(2),
                    preferredFingerprint = fingerprint,
                )
            }
        }
        assertInvalidInput {
            NativeCrypto.openPgp.signDetached(
                content = byteArrayOf(1),
                privateKey = byteArrayOf(2),
                signatureTimeEpochSeconds = -1L,
            )
        }
        assertInvalidInput {
            NativeCrypto.openPgp.openDetachedSigning(
                privateKey = byteArrayOf(2),
                referenceTimeEpochSeconds = -1L,
            )
        }
    }

    @Test
    fun rejectsInvalidEncryptionInputsBeforeLoadingNativeCode() {
        fun encrypt(
            publicKeys: List<ByteArray> = listOf(byteArrayOf(1)),
            signingPrivateKey: ByteArray? = null,
            preferredSigningFingerprint: String = "",
            fileName: String = "message.txt",
            literalTimeEpochSeconds: Long? = null,
            referenceTimeEpochSeconds: Long? = null,
        ) {
            NativeCrypto.openPgp.encrypt(
                content = byteArrayOf(2),
                publicKeys = publicKeys,
                signingPrivateKey = signingPrivateKey,
                preferredSigningFingerprint = preferredSigningFingerprint,
                fileName = fileName,
                armored = false,
                literalTimeEpochSeconds = literalTimeEpochSeconds,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            )
        }

        assertInvalidInput { encrypt(publicKeys = emptyList()) }
        assertInvalidInput { encrypt(publicKeys = listOf(byteArrayOf())) }
        assertInvalidInput { encrypt(signingPrivateKey = byteArrayOf()) }
        assertInvalidInput {
            encrypt(preferredSigningFingerprint = FINGERPRINT)
        }
        assertInvalidInput { encrypt(fileName = " \t") }
        assertInvalidInput { encrypt(literalTimeEpochSeconds = -1L) }
        assertInvalidInput { encrypt(referenceTimeEpochSeconds = -1L) }

        assertInvalidInput {
            NativeCrypto.openPgp.openEncryption(
                publicKeys = emptyList(),
                fileName = "message.txt",
                armored = false,
            )
        }
    }

    @Test
    fun rejectsOversizedInMemoryEncryptionBeforeLoadingNativeCode() {
        val content = ByteArray(NativeCryptoOpenPgp.MAX_IN_MEMORY_PLAINTEXT_BYTES + 1)
        try {
            val failure = assertFailsWith<NativeCryptoException> {
                NativeCrypto.openPgp.encrypt(
                    content = content,
                    publicKeys = listOf(byteArrayOf(1)),
                    fileName = "message.txt",
                    armored = false,
                )
            }

            assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
        } finally {
            content.fill(0)
        }
    }

    @Test
    fun rejectsInvalidDecryptionAndImportInputsBeforeLoadingNativeCode() {
        assertInvalidInput {
            NativeCrypto.openPgp.decrypt(
                content = byteArrayOf(1),
                privateKeys = emptyList(),
            )
        }
        assertInvalidInput {
            NativeCrypto.openPgp.decrypt(
                content = byteArrayOf(1),
                privateKeys = listOf(byteArrayOf()),
            )
        }
        assertInvalidInput {
            NativeCrypto.openPgp.openDecryption(
                privateKeys = listOf(byteArrayOf(1)),
                referenceTimeEpochSeconds = -1L,
            )
        }
        assertInvalidInput {
            NativeCrypto.openPgp.importKey(
                keyData = byteArrayOf(1),
                referenceTimeEpochSeconds = -1L,
            )
        }
    }

    @Test
    fun rejectsNegativeExpirationUpdateTimeBeforeLoadingNativeCode() {
        assertInvalidInput {
            NativeCrypto.openPgp.updateExpiration(
                privateKey = byteArrayOf(1),
                publicKey = byteArrayOf(2),
                expectedPrimaryFingerprint = FINGERPRINT,
                componentFingerprints = listOf(FINGERPRINT),
                expiresAtEpochSeconds = -1L,
                candidateRevocationKeys = emptyList(),
                referenceTimeEpochSeconds = 0L,
            )
        }
    }

    @Test
    fun acceptsMissingPublicKeyWithoutAuthenticatedMetadata() {
        val result = decode(
            OpenPgpVerificationProto(
                status = OpenPgpVerificationStatusProto.MISSING_PUBLIC_KEY,
                keyId = KEY_ID,
                createdAtEpochSeconds = 1_700_000_000L,
            ),
        )

        assertEquals(NativeOpenPgpVerificationStatus.MISSING_PUBLIC_KEY, result.status)
        assertEquals(KEY_ID, result.keyId)
    }

    @Test
    fun rejectsDuplicateWarningsAndClearsTheWirePayload() {
        val payload = ProtoBuf.encodeToByteArray(
            validVerification().copy(
                warnings = listOf(
                    OpenPgpVerificationWarningProto.KEY_EXPIRED.wireValue,
                    OpenPgpVerificationWarningProto.KEY_EXPIRED.wireValue,
                ),
            ),
        )

        val failure = assertFailsWith<NativeCryptoException> {
            decodeOpenPgpVerification(OPERATION, payload)
        }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(payload.all { byte -> byte == 0.toByte() })
    }

    @Test
    fun rejectsAuthenticatedMetadataOnMissingPublicKey() {
        for (
            response in listOf(
                OpenPgpVerificationProto(
                    status = OpenPgpVerificationStatusProto.MISSING_PUBLIC_KEY,
                    keyId = KEY_ID,
                    fingerprint = FINGERPRINT,
                ),
                OpenPgpVerificationProto(
                    status = OpenPgpVerificationStatusProto.MISSING_PUBLIC_KEY,
                    keyId = KEY_ID,
                    userIds = listOf("Alice <alice@example.invalid>"),
                ),
                OpenPgpVerificationProto(
                    status = OpenPgpVerificationStatusProto.MISSING_PUBLIC_KEY,
                    keyId = KEY_ID,
                    warnings = listOf(OpenPgpVerificationWarningProto.KEY_REVOKED.wireValue),
                ),
            )
        ) {
            assertMalformed(response)
        }
    }

    @Test
    fun rejectsVerificationStatusWithoutSignerFingerprint() {
        for (
            status in listOf(
                OpenPgpVerificationStatusProto.VALID,
                OpenPgpVerificationStatusProto.INVALID,
            )
        ) {
            assertMalformed(
                OpenPgpVerificationProto(
                    status = status,
                    keyId = KEY_ID,
                ),
            )
        }
    }

    @Test
    fun rejectsUnspecifiedOrMalformedVerificationFields() {
        assertMalformed(
            validVerification().copy(status = OpenPgpVerificationStatusProto.UNSPECIFIED),
        )
        assertMalformed(validVerification().copy(keyId = KEY_ID.lowercase()))
        assertMalformed(validVerification().copy(fingerprint = "G".repeat(40)))
        assertMalformed(validVerification().copy(createdAtEpochSeconds = -1L))
        assertMalformed(
            validVerification().copy(
                warnings = listOf(OpenPgpVerificationWarningProto.UNSPECIFIED.wireValue),
            ),
        )
        assertMalformed(validVerification().copy(warnings = listOf(99)))
    }

    @Test
    fun clearsFinalPlaintextWhenVerificationMetadataIsMalformed() {
        val plaintext = byteArrayOf(1, 2, 3)
        val response = OpenPgpDecryptFinalProto(
            data = plaintext,
            verification = validVerification().copy(
                status = OpenPgpVerificationStatusProto.UNSPECIFIED,
            ),
        )

        val failure = assertFailsWith<NativeCryptoException> {
            response.toPublicDecryptFinal("open_pgp_decrypt.stream_finish")
        }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(plaintext.all { byte -> byte == 0.toByte() })
    }

    @Test
    fun validatesFinalDecryptionKeyAttribution() {
        val attributed = OpenPgpDecryptFinalProto(
            encrypted = true,
            decryptionKeyFingerprint = FINGERPRINT,
        ).toPublicDecryptFinal("open_pgp_decrypt.stream_finish")
        assertEquals(FINGERPRINT, attributed.decryptionKeyFingerprint)

        val compatible = OpenPgpDecryptFinalProto(
            encrypted = true,
        ).toPublicDecryptFinal("open_pgp_decrypt.stream_finish")
        assertEquals(null, compatible.decryptionKeyFingerprint)

        for (response in listOf(
            OpenPgpDecryptFinalProto(
                data = byteArrayOf(1, 2, 3),
                encrypted = true,
                decryptionKeyFingerprint = "not-a-fingerprint",
            ),
            OpenPgpDecryptFinalProto(
                data = byteArrayOf(1, 2, 3),
                encrypted = false,
                decryptionKeyFingerprint = FINGERPRINT,
            ),
        )) {
            val failure = assertFailsWith<NativeCryptoException> {
                response.toPublicDecryptFinal("open_pgp_decrypt.stream_finish")
            }
            assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
            assertTrue(response.data.all { byte -> byte == 0.toByte() })
        }
    }

    @Test
    fun clearsFinalPlaintextWhenLiteralMetadataIsMalformed() {
        val malformedMetadata = listOf(
            OpenPgpLiteralMetadataProto(format = -1),
            OpenPgpLiteralMetadataProto(format = UByte.MAX_VALUE.toInt() + 1),
            OpenPgpLiteralMetadataProto(modificationTimeEpochSeconds = -1L),
            OpenPgpLiteralMetadataProto(originalSize = -1L),
        )
        for (metadata in malformedMetadata) {
            val plaintext = byteArrayOf(1, 2, 3)
            val response = OpenPgpDecryptFinalProto(
                data = plaintext,
                metadata = metadata,
            )

            val failure = assertFailsWith<NativeCryptoException> {
                response.toPublicDecryptFinal("open_pgp_decrypt.stream_finish")
            }

            assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
            assertTrue(plaintext.all { byte -> byte == 0.toByte() })
        }
    }

    @Test
    fun clearsDecodedExpirationKeyMaterialWhenMetadataIsMissing() {
        val privateKey = byteArrayOf(1, 2, 3)
        val publicKey = byteArrayOf(4, 5, 6)
        val response = expirationResult(
            privateKey = privateKey,
            publicKey = publicKey,
            metadata = null,
        )

        assertMalformedExpiration(response)

        assertTrue(privateKey.all { byte -> byte == 0.toByte() })
        assertTrue(publicKey.all { byte -> byte == 0.toByte() })
    }

    @Test
    fun clearsDecodedExpirationKeyMaterialWhenKeyMaterialIsInvalid() {
        val privateKey = byteArrayOf(1, 2, 3)
        val publicKey = byteArrayOf(4, 5, 6)
        val response = expirationResult(
            privateKey = privateKey,
            publicKey = publicKey,
            fingerprint = "invalid",
        )

        assertMalformedExpiration(response)

        assertTrue(privateKey.all { byte -> byte == 0.toByte() })
        assertTrue(publicKey.all { byte -> byte == 0.toByte() })
    }

    @Test
    fun transfersDecodedExpirationKeyMaterialOwnershipAfterValidation() {
        val privateKey = byteArrayOf(1, 2, 3)
        val publicKey = byteArrayOf(4, 5, 6)

        val result = expirationResult(
            privateKey = privateKey,
            publicKey = publicKey,
        ).toPublicExpirationUpdateResult(EXPIRATION_OPERATION)
            as NativeOpenPgpExpirationUpdateResult.Success

        assertTrue(result.keyMaterial.privateKeyArmored === privateKey)
        assertTrue(result.keyMaterial.publicKeyArmored === publicKey)
        assertTrue(privateKey.any { byte -> byte != 0.toByte() })
        assertTrue(publicKey.any { byte -> byte != 0.toByte() })
    }

    private fun assertMalformed(response: OpenPgpVerificationProto) {
        val failure = assertFailsWith<NativeCryptoException> {
            decode(response)
        }
        assertEquals(OPERATION, failure.operation)
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
    }

    private fun assertMalformedExpiration(response: OpenPgpExpirationUpdateResultProto) {
        val failure = assertFailsWith<NativeCryptoException> {
            response.toPublicExpirationUpdateResult(EXPIRATION_OPERATION)
        }
        assertEquals(EXPIRATION_OPERATION, failure.operation)
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
    }

    private fun assertInvalidInput(block: () -> Unit) {
        assertFailsWith<IllegalArgumentException> { block() }
    }

    private fun decode(response: OpenPgpVerificationProto): NativeOpenPgpVerification =
        decodeOpenPgpVerification(
            operation = OPERATION,
            payload = ProtoBuf.encodeToByteArray(response),
        )

    private fun validVerification() = OpenPgpVerificationProto(
        status = OpenPgpVerificationStatusProto.VALID,
        keyId = KEY_ID,
        fingerprint = FINGERPRINT,
    )

    private fun expirationResult(
        privateKey: ByteArray,
        publicKey: ByteArray,
        fingerprint: String = FINGERPRINT,
        metadata: OpenPgpKeyMetadataProto? = validMetadata(),
    ) = OpenPgpExpirationUpdateResultProto(
        OpenPgpExpirationUpdateSuccessOutcomeProto(
            OpenPgpExpirationUpdateSuccessProto(
                keyMaterial = OpenPgpKeyMaterialProto(
                    privateKeyArmored = privateKey,
                    publicKeyArmored = publicKey,
                    fingerprint = fingerprint,
                ),
                metadata = metadata,
            ),
        ),
    )

    private fun validMetadata() = OpenPgpKeyMetadataProto(
        version = 1,
        keys = listOf(
            OpenPgpKeyMetadataKeyProto(
                keygrip = "A".repeat(40),
                fingerprint = FINGERPRINT,
                algorithm = "rsa",
                capabilities = listOf("sign"),
            ),
        ),
    )

    private companion object {
        const val OPERATION = "open_pgp_verify"
        const val EXPIRATION_OPERATION = "open_pgp_expiration_update"
        const val KEY_ID = "0123456789ABCDEF"
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
    }
}
