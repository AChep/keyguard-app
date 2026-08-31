@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.artemchep.keyguard.nativecrypto

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeCryptoOpenPgpValidationTest {
    @Test
    fun validatesUserIdsUsingStrictUtf8Encoding() {
        assertFalse("\uD800".isValidOpenPgpUserId())
        assertFalse("\uDC00".isValidOpenPgpUserId())
        assertFalse("\uD800A".isValidOpenPgpUserId())
        assertFalse("A\uDC00".isValidOpenPgpUserId())

        assertTrue("Alice \uD83D\uDE00".isValidOpenPgpUserId())
        assertTrue("A".repeat(1_024).isValidOpenPgpUserId())
        assertTrue("é".repeat(512).isValidOpenPgpUserId())
        assertFalse("é".repeat(513).isValidOpenPgpUserId())
    }

    @Test
    fun policyAcceptanceRequiresValidStatusWithoutWarnings() {
        val valid = NativeOpenPgpVerification(
            status = NativeOpenPgpVerificationStatus.VALID,
            keyId = KEY_ID,
            fingerprint = FINGERPRINT,
            userIds = emptyList(),
            createdAtEpochSeconds = null,
            warnings = emptyList(),
        )

        assertTrue(valid.isPolicyAccepted)
        NativeOpenPgpVerificationWarning.entries.forEach { warning ->
            assertFalse(valid.copy(warnings = listOf(warning)).isPolicyAccepted, warning.name)
        }
        assertFalse(
            valid.copy(status = NativeOpenPgpVerificationStatus.INVALID).isPolicyAccepted,
        )
    }

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
                candidateRevocationKeys = emptyList(),
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
                    candidateRevocationKeys = emptyList(),
                    preferredFingerprint = fingerprint,
                )
            }
        }
        assertInvalidInput {
            NativeCrypto.openPgp.signDetached(
                content = byteArrayOf(1),
                privateKey = byteArrayOf(2),
                candidateRevocationKeys = emptyList(),
                signatureTimeEpochSeconds = -1L,
            )
        }
        assertInvalidInput {
            NativeCrypto.openPgp.openDetachedSigning(
                privateKey = byteArrayOf(2),
                candidateRevocationKeys = emptyList(),
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
                candidateRevocationKeys = emptyList(),
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
                candidateRevocationKeys = emptyList(),
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
                    candidateRevocationKeys = emptyList(),
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
    fun decodesCanonicalMetadataAndFailsClosedOnUnknownOperations() {
        val payload = ProtoBuf.encodeToByteArray(
            OpenPgpMetadataResolveResultProto(
                resolution = OpenPgpMetadataResolutionV2Proto(
                    evaluatedAtEpochSeconds = 1_700_000_000L,
                    policyRevision = 2,
                    certificates = listOf(
                        OpenPgpCertificateResolutionV2Proto(
                            index = validMetadataV2Index(
                                agentOperations = listOf(1, 99),
                            ),
                            policy = listOf(
                                OpenPgpComponentPolicyV2Proto(
                                    fingerprint = FINGERPRINT,
                                    allowedNewDataUses = listOf(1, 99),
                                    revocationStatus = 1,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val metadata = decodeOpenPgpMetadataResolution(OPERATION, payload)
            ?: error("v2 metadata must be present")

        assertEquals(1, metadata.certificates.size)
        val certificate = metadata.certificates.single()
        assertEquals(FINGERPRINT, certificate.index.primaryFingerprint)
        assertEquals(
            setOf(NativeOpenPgpAgentOperation.SIGN),
            certificate.index.components.single().agentOperations,
        )
        assertEquals(
            setOf(NativeOpenPgpPolicyUse.SIGN_NEW_DATA),
            certificate.policy.single().allowedNewDataUses,
        )
        assertTrue(payload.all { byte -> byte == 0.toByte() })
    }

    @Test
    fun acceptsCertificateWithNoAgentRoutableComponents() {
        val payload = ProtoBuf.encodeToByteArray(
            OpenPgpMetadataResolveResultProto(
                resolution = OpenPgpMetadataResolutionV2Proto(
                    evaluatedAtEpochSeconds = 1_700_000_000L,
                    policyRevision = 2,
                    certificates = listOf(
                        OpenPgpCertificateResolutionV2Proto(
                            index = validMetadataV2Index(
                                keygrips = emptyList(),
                                agentOperations = emptyList(),
                                storedSecretMaterial = false,
                            ),
                            policy = listOf(
                                OpenPgpComponentPolicyV2Proto(
                                    fingerprint = FINGERPRINT,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val metadata = decodeOpenPgpMetadataResolution(OPERATION, payload)
            ?: error("v2 metadata must be present")

        assertFalse(metadata.certificates.single().index.components.single().storedSecretMaterial)
    }
}

class NativeCryptoOpenPgpVerificationValidationTest {
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
    fun acceptsPolicyConflictAsAnAuthenticatedSignerWarning() {
        val result = decode(
            validVerification().copy(
                warnings = listOf(OpenPgpVerificationWarningProto.POLICY_CONFLICT.wireValue),
            ),
        )

        assertEquals(NativeOpenPgpVerificationStatus.VALID, result.status)
        assertEquals(
            listOf(NativeOpenPgpVerificationWarning.POLICY_CONFLICT),
            result.warnings,
        )
    }

    @Test
    fun preservesEveryLeafSignatureResultAndRejectsNestedResultTrees() {
        val invalid = validVerification().copy(status = OpenPgpVerificationStatusProto.INVALID)
        val missing = OpenPgpVerificationProto(
            status = OpenPgpVerificationStatusProto.MISSING_PUBLIC_KEY,
            keyId = KEY_ID,
        )
        val result = decode(
            validVerification().copy(signatures = listOf(invalid, missing)),
        )

        assertEquals(
            listOf(
                NativeOpenPgpVerificationStatus.INVALID,
                NativeOpenPgpVerificationStatus.MISSING_PUBLIC_KEY,
            ),
            result.signatures.map { signature -> signature.status },
        )
        assertMalformed(
            validVerification().copy(
                signatures = listOf(invalid.copy(signatures = listOf(missing))),
            ),
        )
    }

    @Test
    fun rejectsDuplicateWarningsAndClearsTheWirePayload() {
        val payload = ProtoBuf.encodeToByteArray(
            validVerification().copy(
                warnings = listOf(
                    OpenPgpVerificationWarningProto.POLICY_CONFLICT.wireValue,
                    OpenPgpVerificationWarningProto.POLICY_CONFLICT.wireValue,
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
            warnings = listOf(OpenPgpDecryptionWarningProto.WEAK_RSA_KEY.wireValue),
        ).toPublicDecryptFinal("open_pgp_decrypt.stream_finish")
        assertEquals(FINGERPRINT, attributed.decryptionKeyFingerprint)
        assertEquals(
            listOf(NativeOpenPgpDecryptionWarning.WEAK_RSA_KEY),
            attributed.warnings,
        )

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
            OpenPgpDecryptFinalProto(
                data = byteArrayOf(1, 2, 3),
                encrypted = false,
                warnings = listOf(OpenPgpDecryptionWarningProto.WEAK_RSA_KEY.wireValue),
            ),
            OpenPgpDecryptFinalProto(
                data = byteArrayOf(1, 2, 3),
                encrypted = true,
                warnings = listOf(OpenPgpDecryptionWarningProto.UNSPECIFIED.wireValue),
            ),
            OpenPgpDecryptFinalProto(
                data = byteArrayOf(1, 2, 3),
                encrypted = true,
                warnings = listOf(99),
            ),
            OpenPgpDecryptFinalProto(
                data = byteArrayOf(1, 2, 3),
                encrypted = true,
                warnings = listOf(
                    OpenPgpDecryptionWarningProto.ELGAMAL_KEY.wireValue,
                    OpenPgpDecryptionWarningProto.ELGAMAL_KEY.wireValue,
                ),
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
            certificateIndex = null,
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
        certificateIndex: OpenPgpCertificateIndexV2Proto? = validMetadataV2Index(),
    ) = OpenPgpExpirationUpdateResultProto(
        OpenPgpExpirationUpdateSuccessOutcomeProto(
            OpenPgpExpirationUpdateSuccessProto(
                keyMaterial = OpenPgpKeyMaterialProto(
                    privateKeyArmored = privateKey,
                    publicKeyArmored = publicKey,
                    fingerprint = fingerprint,
                ),
                certificateIndex = certificateIndex,
            ),
        ),
    )
}

class NativeCryptoOpenPgpMetadataPolicyValidationTest {
    @Test
    fun decodesRenewalAuthorizationAndDegradesUnknownValuesToNone() {
        // An unknown or unspecified renewal value must read as "no renewal", never
        // fail the payload: the field is additive and older or newer natives may
        // send anything.
        val expected = mapOf(
            0 to NativeOpenPgpRenewalAuthorization.NONE,
            1 to NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
            2 to NativeOpenPgpRenewalAuthorization.TEMPLATE_ONLY,
            3 to NativeOpenPgpRenewalAuthorization.NONE,
            99 to NativeOpenPgpRenewalAuthorization.NONE,
            -1 to NativeOpenPgpRenewalAuthorization.NONE,
        )
        expected.forEach { (wireValue, renewal) ->
            val payload = ProtoBuf.encodeToByteArray(
                OpenPgpMetadataResolveResultProto(
                    resolution = OpenPgpMetadataResolutionV2Proto(
                        evaluatedAtEpochSeconds = 1_700_000_000L,
                        policyRevision = 2,
                        certificates = listOf(
                            OpenPgpCertificateResolutionV2Proto(
                                index = validMetadataV2Index(),
                                policy = listOf(
                                    OpenPgpComponentPolicyV2Proto(
                                        fingerprint = FINGERPRINT,
                                        allowedNewDataUses = listOf(1),
                                        renewal = wireValue,
                                        revocationStatus = 1,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val metadata = decodeOpenPgpMetadataResolution(OPERATION, payload)
                ?: error("v2 metadata must be present")
            assertEquals(
                renewal,
                metadata.certificates.single().policy.single().renewal,
            )
        }
    }

    @Test
    fun unsupportedPolicyRevisionReportsNoRenewalAuthorization() {
        val payload = ProtoBuf.encodeToByteArray(
            OpenPgpMetadataResolveResultProto(
                resolution = OpenPgpMetadataResolutionV2Proto(
                    evaluatedAtEpochSeconds = 1_700_000_000L,
                    policyRevision = 3,
                    certificates = listOf(
                        OpenPgpCertificateResolutionV2Proto(
                            index = validMetadataV2Index(),
                            policy = listOf(
                                OpenPgpComponentPolicyV2Proto(
                                    fingerprint = FINGERPRINT,
                                    allowedNewDataUses = listOf(1),
                                    renewal = 1,
                                    revocationStatus = 1,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val metadata = decodeOpenPgpMetadataResolution(OPERATION, payload)
            ?: error("v2 metadata must be present")
        val policy = metadata.certificates.single().policy.single()
        assertTrue(policy.allowedNewDataUses.isEmpty())
        assertEquals(NativeOpenPgpRenewalAuthorization.NONE, policy.renewal)
        assertEquals(NativeOpenPgpRevocationStatus.INDETERMINATE, policy.revocationStatus)
    }

    @Test
    fun revocationStatusDefaultsAndUnknownValuesNeverAuthorizeUse() {
        val expected = mapOf(
            0 to NativeOpenPgpRevocationStatus.INDETERMINATE,
            1 to NativeOpenPgpRevocationStatus.NOT_REVOKED,
            2 to NativeOpenPgpRevocationStatus.REVOKED,
            3 to NativeOpenPgpRevocationStatus.INDETERMINATE,
            99 to NativeOpenPgpRevocationStatus.INDETERMINATE,
            -1 to NativeOpenPgpRevocationStatus.INDETERMINATE,
        )
        for (revision in listOf(1, 2, 3)) {
            for ((wireValue, status) in expected) {
                val payload = ProtoBuf.encodeToByteArray(
                    OpenPgpMetadataResolveResultProto(
                        resolution = OpenPgpMetadataResolutionV2Proto(
                            evaluatedAtEpochSeconds = 1_700_000_000L,
                            policyRevision = revision,
                            certificates = listOf(
                                OpenPgpCertificateResolutionV2Proto(
                                    index = validMetadataV2Index(),
                                    policy = listOf(
                                        OpenPgpComponentPolicyV2Proto(
                                            fingerprint = FINGERPRINT,
                                            allowedNewDataUses = listOf(1, 2),
                                            renewal = 1,
                                            revocationStatus = wireValue,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                val metadata = decodeOpenPgpMetadataResolution(OPERATION, payload)
                    ?: error("metadata must be present")
                val policy = metadata.certificates.single().policy.single()
                val expectedStatus = if (revision == 2) status else NativeOpenPgpRevocationStatus.INDETERMINATE
                assertEquals(expectedStatus, policy.revocationStatus)
                if (expectedStatus == NativeOpenPgpRevocationStatus.NOT_REVOKED) {
                    assertEquals(
                        setOf(NativeOpenPgpPolicyUse.SIGN_NEW_DATA, NativeOpenPgpPolicyUse.ENCRYPT_NEW_DATA),
                        policy.allowedNewDataUses,
                    )
                    assertEquals(NativeOpenPgpRenewalAuthorization.AUTHENTICATED, policy.renewal)
                } else {
                    assertTrue(policy.allowedNewDataUses.isEmpty())
                    assertEquals(NativeOpenPgpRenewalAuthorization.NONE, policy.renewal)
                }
            }
        }
    }

    @Test
    fun publicKeyAuthenticationFlagsRoundTripAndDefaultToFalseOnOldPayloads() {
        val proto = OpenPgpPublicKeyInfoProto(
            fingerprint = FINGERPRINT,
            keyId = KEY_ID,
            algorithm = "RSA",
            publicKeyArmored = "public",
            authenticated = true,
            renewal = 2,
            subkeys = listOf(
                OpenPgpPublicSubKeyInfoProto(
                    fingerprint = FINGERPRINT,
                    keyId = KEY_ID,
                    algorithm = "RSA",
                    authenticated = false,
                ),
            ),
        )
        val decoded = ProtoBuf.decodeFromByteArray<OpenPgpPublicKeyInfoProto>(
            ProtoBuf.encodeToByteArray(proto),
        )
        assertEquals(proto, decoded)
        assertTrue(decoded.authenticated)
        assertEquals(2, decoded.renewal)
        assertFalse(decoded.subkeys.single().authenticated)

        // A payload from a native build that predates the field.
        val legacy = ProtoBuf.decodeFromByteArray<OpenPgpPublicKeyInfoProto>(
            ProtoBuf.encodeToByteArray(
                OpenPgpPublicKeyInfoProto(
                    fingerprint = FINGERPRINT,
                    keyId = KEY_ID,
                    algorithm = "RSA",
                    publicKeyArmored = "public",
                ),
            ),
        )
        assertFalse(legacy.authenticated)
        assertEquals(0, legacy.renewal)
    }

    @Test
    fun publicKeyUserIdDetailsRoundTripAndRejectMalformedIdentityIds() {
        val identityId = "v1:${"A".repeat(64)}"
        val detail = OpenPgpUserIdInfoProto(
            identityId = identityId,
            userId = "Alice <alice@example.com>",
        )
        val payload = publicKeyParsePayloadWithUserIdDetail(detail)

        val result = decodeOpenPgpPublicKeyParseResult(PARSE_OPERATION, payload)
        val key = (result as NativeOpenPgpPublicKeyParseResult.Success).keys.single()
        assertEquals(
            listOf(
                NativeOpenPgpUserIdInfo(
                    identityId = identityId,
                    userId = detail.userId,
                ),
            ),
            key.userIdDetails,
        )

        listOf(
            "v2:${"A".repeat(64)}",
            "v1:${"A".repeat(63)}",
            "v1:${"a".repeat(64)}",
            "v1:${"G".repeat(64)}",
        ).forEach { malformedIdentityId ->
            val malformedPayload = publicKeyParsePayloadWithUserIdDetail(
                detail.copy(identityId = malformedIdentityId),
            )

            val failure = assertFailsWith<NativeCryptoException>(malformedIdentityId) {
                decodeOpenPgpPublicKeyParseResult(PARSE_OPERATION, malformedPayload)
            }
            assertEquals(PARSE_OPERATION, failure.operation)
            assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        }
    }

    @Test
    fun publicKeyRenewalTierDecodesAndDegradesUnknownValuesToNone() {
        // The parse path's renewal tier is what tells an unauthenticated but
        // renewable key from one no renewal can repair. An unspecified or
        // unknown value must read as "no renewal", never fail the payload.
        val expected = mapOf(
            0 to NativeOpenPgpRenewalAuthorization.NONE,
            1 to NativeOpenPgpRenewalAuthorization.AUTHENTICATED,
            2 to NativeOpenPgpRenewalAuthorization.TEMPLATE_ONLY,
            3 to NativeOpenPgpRenewalAuthorization.NONE,
            99 to NativeOpenPgpRenewalAuthorization.NONE,
            -1 to NativeOpenPgpRenewalAuthorization.NONE,
        )
        expected.forEach { (wireValue, renewal) ->
            val payload = ProtoBuf.encodeToByteArray(
                OpenPgpPublicKeyParseResultProto(
                    OpenPgpPublicKeyParseSuccessOutcomeProto(
                        OpenPgpPublicKeyParseSuccessProto(
                            keys = listOf(
                                OpenPgpPublicKeyInfoProto(
                                    fingerprint = FINGERPRINT,
                                    keyId = KEY_ID,
                                    algorithm = "RSA",
                                    publicKeyArmored = "public",
                                    renewal = wireValue,
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val result = decodeOpenPgpPublicKeyParseResult(PARSE_OPERATION, payload)
            val key = (result as NativeOpenPgpPublicKeyParseResult.Success).keys.single()
            assertEquals(renewal, key.renewal)
        }

        // A payload from a native build that predates the field.
        val legacyPayload = ProtoBuf.encodeToByteArray(
            OpenPgpPublicKeyParseResultProto(
                OpenPgpPublicKeyParseSuccessOutcomeProto(
                    OpenPgpPublicKeyParseSuccessProto(
                        keys = listOf(
                            OpenPgpPublicKeyInfoProto(
                                fingerprint = FINGERPRINT,
                                keyId = KEY_ID,
                                algorithm = "RSA",
                                publicKeyArmored = "public",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val legacy = decodeOpenPgpPublicKeyParseResult(PARSE_OPERATION, legacyPayload)
        val legacyKey = (legacy as NativeOpenPgpPublicKeyParseResult.Success).keys.single()
        assertEquals(
            NativeOpenPgpRenewalAuthorization.NONE,
            legacyKey.renewal,
        )
        assertTrue(legacyKey.userIdDetails.isEmpty())
    }
}

private fun publicKeyParsePayloadWithUserIdDetail(
    detail: OpenPgpUserIdInfoProto,
): ByteArray = ProtoBuf.encodeToByteArray(
    OpenPgpPublicKeyParseResultProto(
        OpenPgpPublicKeyParseSuccessOutcomeProto(
            OpenPgpPublicKeyParseSuccessProto(
                keys = listOf(
                    OpenPgpPublicKeyInfoProto(
                        fingerprint = FINGERPRINT,
                        keyId = KEY_ID,
                        algorithm = "RSA",
                        publicKeyArmored = "public",
                        userIdDetails = listOf(detail),
                    ),
                ),
            ),
        ),
    ),
)

private fun assertInvalidInput(block: () -> Unit) {
    assertFailsWith<IllegalArgumentException> { block() }
}

private fun validMetadataV2Index(
    keygrips: List<String> = listOf("A".repeat(40)),
    agentOperations: List<Int> = listOf(1),
    storedSecretMaterial: Boolean = true,
) = OpenPgpCertificateIndexV2Proto(
    primaryFingerprint = FINGERPRINT,
    components = listOf(
        OpenPgpKeyComponentIndexV2Proto(
            fingerprint = FINGERPRINT,
            role = 1,
            publicKeyAlgorithmId = 1,
            algorithm = "RSA",
            keygrips = keygrips,
            storedSecretMaterial = storedSecretMaterial,
            agentOperations = agentOperations,
        ),
    ),
)

private const val OPERATION = "open_pgp_verify"
private const val PARSE_OPERATION = "open_pgp_public_key_parse"
private const val EXPIRATION_OPERATION = "open_pgp_expiration_update"
private const val KEY_ID = "0123456789ABCDEF"
private const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
