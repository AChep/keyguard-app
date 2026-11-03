package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeCryptoOpenPgpUserIdMutationValidationTest {
    @Test
    fun newUserIdsRejectUnicodeLineSeparators() {
        for (separator in listOf('\n', '\r', '\u0085', '\u2028', '\u2029')) {
            assertEquals(false, "Alice${separator}Injected".isValidOpenPgpUserId())
        }
        assertTrue("Аліса <alice@example.test>".isValidOpenPgpUserId())
    }

    @Test
    fun nativeBridgePreservesRetryTimestampAndSharedRevocationReason() {
        val created = 1_700_000_000L
        val material = NativeCrypto.openPgp.generateKey(
            kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
            userId = "First <first@example.test>",
            creationTimeEpochSeconds = created,
        )
        try {
            val parsed = assertIs<NativeOpenPgpPublicKeyParseResult.Success>(
                NativeCrypto.openPgp.parsePublicKeys(material.publicKeyArmored, created),
            ).keys.single()
            val retry = assertIs<NativeOpenPgpUserIdUpdateResult.Error>(
                NativeCrypto.openPgp.updateUserId(
                    material.privateKeyArmored, material.publicKeyArmored, material.fingerprint,
                    NativeOpenPgpUserIdChange.Add("Second <second@example.test>"), emptyList(), created,
                ),
            )
            assertEquals(NativeOpenPgpUserIdMutationError.TIME_CONFLICT, retry.reason)
            assertEquals(created + 1, retry.retryAtEpochSeconds)
            val lastIdentity = assertIs<NativeOpenPgpUserIdRevocationResult.Error>(
                NativeCrypto.openPgp.revokeUserId(
                    material.privateKeyArmored, material.publicKeyArmored, material.fingerprint,
                    parsed.userIdDetails.single().identityId, emptyList(), created + 1,
                ),
            )
            assertEquals(NativeOpenPgpUserIdMutationError.LAST_USER_ID, lastIdentity.reason)
            assertEquals(null, lastIdentity.retryAtEpochSeconds)
        } finally {
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    @Test
    fun mutationErrorsCarryExactRetryTimeAcrossAllOperations() {
        val error = OpenPgpUserIdMutationErrorProto(OpenPgpUserIdMutationErrorReasonProto.TIME_CONFLICT, 123L)
        val update = OpenPgpUserIdUpdateResultProto(OpenPgpUserIdUpdateErrorOutcomeProto(error))
            .toPublicUserIdUpdateResult("update", FINGERPRINT)
        val replace = OpenPgpUserIdReplacementResultProto(OpenPgpUserIdReplacementErrorOutcomeProto(error))
            .toPublicUserIdReplacementResult(REPLACEMENT_OPERATION, FINGERPRINT)
        val revoke = OpenPgpUserIdRevocationResultProto(OpenPgpUserIdRevocationErrorOutcomeProto(error))
            .toPublicUserIdRevocationResult(REVOCATION_OPERATION, FINGERPRINT)
        assertEquals(123L, assertIs<NativeOpenPgpUserIdUpdateResult.Error>(update).retryAtEpochSeconds)
        assertEquals(123L, assertIs<NativeOpenPgpUserIdReplacementResult.Error>(replace).retryAtEpochSeconds)
        assertEquals(123L, assertIs<NativeOpenPgpUserIdRevocationResult.Error>(revoke).retryAtEpochSeconds)
    }

    @Test
    fun rejectsRetryTimesOnPermanentErrorsAndOutsideSignatureTimestampRange() {
        val errors = listOf(
            OpenPgpUserIdMutationErrorProto(OpenPgpUserIdMutationErrorReasonProto.POLICY_CONFLICT, 123L),
            OpenPgpUserIdMutationErrorProto(OpenPgpUserIdMutationErrorReasonProto.TIME_CONFLICT, -1L),
            OpenPgpUserIdMutationErrorProto(OpenPgpUserIdMutationErrorReasonProto.TIME_CONFLICT, 0L),
            OpenPgpUserIdMutationErrorProto(OpenPgpUserIdMutationErrorReasonProto.TIME_CONFLICT, 0x1_0000_0000L),
        )
        errors.forEach { error ->
            assertFailsWith<NativeCryptoException> {
                OpenPgpUserIdUpdateResultProto(OpenPgpUserIdUpdateErrorOutcomeProto(error))
                    .toPublicUserIdUpdateResult("update", FINGERPRINT)
            }
        }
    }

    @Test
    fun updateResultRejectsMismatchedFingerprintAndErasesOwnedBuffers() {
        val privateKey = byteArrayOf(1, 2)
        val publicKey = byteArrayOf(3, 4)
        val proto = OpenPgpUserIdUpdateResultProto(
            OpenPgpUserIdUpdateSuccessOutcomeProto(
                OpenPgpUserIdUpdateSuccessProto(
                    keyMaterial = keyMaterial(privateKey, publicKey),
                    certificateIndex = validCertificateIndex(),
                    changed = true,
                ),
            ),
        )
        assertFailsWith<NativeCryptoException> {
            proto.toPublicUserIdUpdateResult("open_pgp_user_id_update", "00".repeat(20))
        }
        assertTrue(privateKey.all { it == 0.toByte() })
        assertTrue(publicKey.all { it == 0.toByte() })
    }

    @Test
    fun revocationResultUsesCertificateIndexAndTransfersOwnedBuffers() {
        val privateKey = byteArrayOf(1)
        val publicKey = byteArrayOf(2)
        val certificate = byteArrayOf(3)
        val result =
            OpenPgpUserIdRevocationResultProto(
                OpenPgpUserIdRevocationSuccessOutcomeProto(
                    OpenPgpUserIdRevocationSuccessProto(
                        keyMaterial = keyMaterial(privateKey, publicKey),
                        revocationCertificateArmored = certificate,
                        changed = true,
                        effectiveAtEpochSeconds = 1L,
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            ).toPublicUserIdRevocationResult(
                operation = REVOCATION_OPERATION,
                expectedPrimaryFingerprint = FINGERPRINT,
            )

        val success = assertIs<NativeOpenPgpUserIdRevocationResult.Success>(result)
        assertTrue(success.keyMaterial.privateKeyArmored === privateKey)
        assertTrue(success.keyMaterial.publicKeyArmored === publicKey)
        assertTrue(success.revocationCertificateArmored === certificate)
        assertEquals(FINGERPRINT, success.certificateIndex.primaryFingerprint)
    }

    @Test
    fun revocationResultAcceptsConsistentFingerprintWhenExpectedIsUnspecified() {
        val result =
            OpenPgpUserIdRevocationResultProto(
                OpenPgpUserIdRevocationSuccessOutcomeProto(
                    OpenPgpUserIdRevocationSuccessProto(
                        keyMaterial = keyMaterial(byteArrayOf(1), byteArrayOf(2)),
                        revocationCertificateArmored = byteArrayOf(3),
                        changed = true,
                        effectiveAtEpochSeconds = 1L,
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            ).toPublicUserIdRevocationResult(
                operation = REVOCATION_OPERATION,
                expectedPrimaryFingerprint = "",
            )

        val success = assertIs<NativeOpenPgpUserIdRevocationResult.Success>(result)
        assertEquals(FINGERPRINT, success.keyMaterial.fingerprint)
        assertEquals(FINGERPRINT, success.certificateIndex.primaryFingerprint)
    }

    @Test
    fun replacementResultUsesCertificateIndexAndTransfersOwnedBuffers() {
        val privateKey = byteArrayOf(4)
        val publicKey = byteArrayOf(5)
        val certificate = byteArrayOf(6)
        val result =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementSuccessOutcomeProto(
                    OpenPgpUserIdReplacementSuccessProto(
                        keyMaterial = keyMaterial(privateKey, publicKey),
                        replacementCertificateArmored = certificate,
                        changed = true,
                        effectiveAtEpochSeconds = 2L,
                        oldIdentityId = IDENTITY_ID,
                        newIdentityId = NEW_IDENTITY_ID,
                        primaryUserId = "Alice <alice@example.test>",
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            ).toPublicUserIdReplacementResult(
                operation = REPLACEMENT_OPERATION,
                expectedPrimaryFingerprint = FINGERPRINT,
            )

        val success = assertIs<NativeOpenPgpUserIdReplacementResult.Success>(result)
        assertTrue(success.keyMaterial.privateKeyArmored === privateKey)
        assertTrue(success.keyMaterial.publicKeyArmored === publicKey)
        assertTrue(success.replacementCertificateArmored === certificate)
        assertEquals(FINGERPRINT, success.certificateIndex.primaryFingerprint)
    }

    @Test
    fun replacementResultAcceptsConsistentFingerprintWhenExpectedIsUnspecified() {
        val result =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementSuccessOutcomeProto(
                    OpenPgpUserIdReplacementSuccessProto(
                        keyMaterial = keyMaterial(byteArrayOf(4), byteArrayOf(5)),
                        replacementCertificateArmored = byteArrayOf(6),
                        changed = true,
                        effectiveAtEpochSeconds = 2L,
                        oldIdentityId = IDENTITY_ID,
                        newIdentityId = NEW_IDENTITY_ID,
                        primaryUserId = "Alice <alice@example.test>",
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            ).toPublicUserIdReplacementResult(
                operation = REPLACEMENT_OPERATION,
                expectedPrimaryFingerprint = "",
            )

        val success = assertIs<NativeOpenPgpUserIdReplacementResult.Success>(result)
        assertEquals(FINGERPRINT, success.keyMaterial.fingerprint)
        assertEquals(FINGERPRINT, success.certificateIndex.primaryFingerprint)
    }

    @Test
    fun mutationResultsAcceptEmptyArtifactForLocalChangeAndNoOp() {
        listOf(true, false).forEach { changed ->
            val revocation =
                OpenPgpUserIdRevocationResultProto(
                    OpenPgpUserIdRevocationSuccessOutcomeProto(
                        OpenPgpUserIdRevocationSuccessProto(
                            keyMaterial = keyMaterial(byteArrayOf(1), byteArrayOf(2)),
                            revocationCertificateArmored = byteArrayOf(),
                            changed = changed,
                            effectiveAtEpochSeconds = 1L,
                            certificateIndex = validCertificateIndex(),
                        ),
                    ),
                ).toPublicUserIdRevocationResult(
                    operation = REVOCATION_OPERATION,
                    expectedPrimaryFingerprint = FINGERPRINT,
                )
            val revocationSuccess =
                assertIs<NativeOpenPgpUserIdRevocationResult.Success>(revocation)
            assertEquals(changed, revocationSuccess.changed)
            assertTrue(revocationSuccess.revocationCertificateArmored.isEmpty())

            val replacement =
                OpenPgpUserIdReplacementResultProto(
                    OpenPgpUserIdReplacementSuccessOutcomeProto(
                        OpenPgpUserIdReplacementSuccessProto(
                            keyMaterial = keyMaterial(byteArrayOf(4), byteArrayOf(5)),
                            replacementCertificateArmored = byteArrayOf(),
                            changed = changed,
                            effectiveAtEpochSeconds = 2L,
                            oldIdentityId = IDENTITY_ID,
                            newIdentityId = NEW_IDENTITY_ID,
                            primaryUserId = "Alice <alice@example.test>",
                            certificateIndex = validCertificateIndex(),
                        ),
                    ),
                ).toPublicUserIdReplacementResult(
                    operation = REPLACEMENT_OPERATION,
                    expectedPrimaryFingerprint = FINGERPRINT,
                )
            val replacementSuccess =
                assertIs<NativeOpenPgpUserIdReplacementResult.Success>(replacement)
            assertEquals(changed, replacementSuccess.changed)
            assertTrue(replacementSuccess.replacementCertificateArmored.isEmpty())
        }
    }

    @Test
    fun revocationResultRejectsArtifactForNoOpAndClearsEveryOwnedOutput() {
        val privateKey = byteArrayOf(1)
        val publicKey = byteArrayOf(2)
        val certificate = byteArrayOf(3)
        val response =
            OpenPgpUserIdRevocationResultProto(
                OpenPgpUserIdRevocationSuccessOutcomeProto(
                    OpenPgpUserIdRevocationSuccessProto(
                        keyMaterial = keyMaterial(privateKey, publicKey),
                        revocationCertificateArmored = certificate,
                        changed = false,
                        effectiveAtEpochSeconds = 1L,
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicUserIdRevocationResult(REVOCATION_OPERATION, FINGERPRINT)
            }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(privateKey.all { it == 0.toByte() })
        assertTrue(publicKey.all { it == 0.toByte() })
        assertTrue(certificate.all { it == 0.toByte() })
    }

    @Test
    fun replacementResultRejectsArtifactForNoOpAndClearsEveryOwnedOutput() {
        val privateKey = byteArrayOf(4)
        val publicKey = byteArrayOf(5)
        val certificate = byteArrayOf(6)
        val response =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementSuccessOutcomeProto(
                    OpenPgpUserIdReplacementSuccessProto(
                        keyMaterial = keyMaterial(privateKey, publicKey),
                        replacementCertificateArmored = certificate,
                        changed = false,
                        effectiveAtEpochSeconds = 2L,
                        oldIdentityId = IDENTITY_ID,
                        newIdentityId = NEW_IDENTITY_ID,
                        primaryUserId = "Alice <alice@example.test>",
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicUserIdReplacementResult(REPLACEMENT_OPERATION, FINGERPRINT)
            }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(privateKey.all { it == 0.toByte() })
        assertTrue(publicKey.all { it == 0.toByte() })
        assertTrue(certificate.all { it == 0.toByte() })
    }

    @Test
    fun userIdMutationResultsMapExtendedPolicyErrors() {
        val revocation =
            OpenPgpUserIdRevocationResultProto(
                OpenPgpUserIdRevocationErrorOutcomeProto(
                    OpenPgpUserIdMutationErrorProto(
                        OpenPgpUserIdMutationErrorReasonProto.CERTIFICATE_REVOKED,
                    ),
                ),
            ).toPublicUserIdRevocationResult(REVOCATION_OPERATION, FINGERPRINT)
        val replacement =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementErrorOutcomeProto(
                    OpenPgpUserIdMutationErrorProto(
                        OpenPgpUserIdMutationErrorReasonProto.UNSUPPORTED_SIGNING_HASH,
                    ),
                ),
            ).toPublicUserIdReplacementResult(REPLACEMENT_OPERATION, FINGERPRINT)

        assertEquals(
            NativeOpenPgpUserIdMutationError.CERTIFICATE_REVOKED,
            assertIs<NativeOpenPgpUserIdRevocationResult.Error>(revocation).reason,
        )
        assertEquals(
            NativeOpenPgpUserIdMutationError.UNSUPPORTED_SIGNING_HASH,
            assertIs<NativeOpenPgpUserIdReplacementResult.Error>(replacement).reason,
        )
    }

    @Test
    fun replacementResultMapsPermanentPolicyConflict() {
        val replacement =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementErrorOutcomeProto(
                    OpenPgpUserIdMutationErrorProto(
                        OpenPgpUserIdMutationErrorReasonProto.POLICY_CONFLICT,
                    ),
                ),
            ).toPublicUserIdReplacementResult(REPLACEMENT_OPERATION, FINGERPRINT)

        assertEquals(
            NativeOpenPgpUserIdMutationError.POLICY_CONFLICT,
            assertIs<NativeOpenPgpUserIdReplacementResult.Error>(replacement).reason,
        )
    }

    @Test
    fun revocationResultRejectsInconsistentFingerprintsAndClearsEveryOwnedOutput() {
        val privateKey = byteArrayOf(1)
        val publicKey = byteArrayOf(2)
        val certificate = byteArrayOf(3)
        val response =
            OpenPgpUserIdRevocationResultProto(
                OpenPgpUserIdRevocationSuccessOutcomeProto(
                    OpenPgpUserIdRevocationSuccessProto(
                        keyMaterial =
                            OpenPgpKeyMaterialProto(
                                privateKeyArmored = privateKey,
                                publicKeyArmored = publicKey,
                                fingerprint = "A".repeat(40),
                            ),
                        revocationCertificateArmored = certificate,
                        changed = true,
                        effectiveAtEpochSeconds = 1L,
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicUserIdRevocationResult(REVOCATION_OPERATION, "")
            }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(privateKey.all { it == 0.toByte() })
        assertTrue(publicKey.all { it == 0.toByte() })
        assertTrue(certificate.all { it == 0.toByte() })
    }

    @Test
    fun replacementResultRejectsInconsistentFingerprintsAndClearsEveryOwnedOutput() {
        val privateKey = byteArrayOf(4)
        val publicKey = byteArrayOf(5)
        val certificate = byteArrayOf(6)
        val response =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementSuccessOutcomeProto(
                    OpenPgpUserIdReplacementSuccessProto(
                        keyMaterial =
                            OpenPgpKeyMaterialProto(
                                privateKeyArmored = privateKey,
                                publicKeyArmored = publicKey,
                                fingerprint = "A".repeat(40),
                            ),
                        replacementCertificateArmored = certificate,
                        changed = true,
                        effectiveAtEpochSeconds = 2L,
                        oldIdentityId = IDENTITY_ID,
                        newIdentityId = NEW_IDENTITY_ID,
                        primaryUserId = "Alice <alice@example.test>",
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicUserIdReplacementResult(REPLACEMENT_OPERATION, "")
            }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(privateKey.all { it == 0.toByte() })
        assertTrue(publicKey.all { it == 0.toByte() })
        assertTrue(certificate.all { it == 0.toByte() })
    }

    @Test
    fun replacementResultRejectsMalformedOldIdentityIdAndClearsEveryOwnedOutput() {
        assertMalformedReplacementIdentityIdClearsEveryOwnedOutput(
            oldIdentityId = "invalid-old-identity-id",
            newIdentityId = NEW_IDENTITY_ID,
        )
    }

    @Test
    fun replacementResultRejectsMalformedNewIdentityIdAndClearsEveryOwnedOutput() {
        assertMalformedReplacementIdentityIdClearsEveryOwnedOutput(
            oldIdentityId = IDENTITY_ID,
            newIdentityId = "invalid-new-identity-id",
        )
    }

    private fun keyMaterial(
        privateKey: ByteArray,
        publicKey: ByteArray,
    ) = OpenPgpKeyMaterialProto(
        privateKeyArmored = privateKey,
        publicKeyArmored = publicKey,
        fingerprint = FINGERPRINT,
    )

    private fun assertMalformedReplacementIdentityIdClearsEveryOwnedOutput(
        oldIdentityId: String,
        newIdentityId: String,
    ) {
        val privateKey = byteArrayOf(4)
        val publicKey = byteArrayOf(5)
        val certificate = byteArrayOf(6)
        val response =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementSuccessOutcomeProto(
                    OpenPgpUserIdReplacementSuccessProto(
                        keyMaterial = keyMaterial(privateKey, publicKey),
                        replacementCertificateArmored = certificate,
                        changed = true,
                        effectiveAtEpochSeconds = 2L,
                        oldIdentityId = oldIdentityId,
                        newIdentityId = newIdentityId,
                        primaryUserId = "Alice <alice@example.test>",
                        certificateIndex = validCertificateIndex(),
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicUserIdReplacementResult(REPLACEMENT_OPERATION, FINGERPRINT)
            }

        assertEquals(REPLACEMENT_OPERATION, failure.operation)
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(privateKey.all { it == 0.toByte() })
        assertTrue(publicKey.all { it == 0.toByte() })
        assertTrue(certificate.all { it == 0.toByte() })
    }

    private fun validCertificateIndex() =
        OpenPgpCertificateIndexV2Proto(
            primaryFingerprint = FINGERPRINT,
            components =
                listOf(
                    OpenPgpKeyComponentIndexV2Proto(
                        fingerprint = FINGERPRINT,
                        role = 1,
                        publicKeyAlgorithmId = 1,
                        algorithm = "RSA",
                        keygrips = listOf("A".repeat(40)),
                        storedSecretMaterial = true,
                        agentOperations = listOf(1),
                    ),
                ),
        )

    private companion object {
        const val REVOCATION_OPERATION = "open_pgp_user_id_revocation"
        const val REPLACEMENT_OPERATION = "open_pgp_user_id_replacement"
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
        const val IDENTITY_ID = "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val NEW_IDENTITY_ID = "v1:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
    }
}
