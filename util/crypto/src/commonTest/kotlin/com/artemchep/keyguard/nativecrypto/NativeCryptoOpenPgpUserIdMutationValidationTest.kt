package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeCryptoOpenPgpUserIdMutationValidationTest {
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
                    OpenPgpUserIdRevocationErrorProto(
                        OpenPgpUserIdRevocationErrorReasonProto.CERTIFICATE_REVOKED,
                    ),
                ),
            ).toPublicUserIdRevocationResult(REVOCATION_OPERATION, FINGERPRINT)
        val replacement =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementErrorOutcomeProto(
                    OpenPgpUserIdReplacementErrorProto(
                        OpenPgpUserIdReplacementErrorReasonProto.UNSUPPORTED_SIGNING_HASH,
                    ),
                ),
            ).toPublicUserIdReplacementResult(REPLACEMENT_OPERATION, FINGERPRINT)

        assertEquals(
            NativeOpenPgpUserIdRevocationError.CERTIFICATE_REVOKED,
            assertIs<NativeOpenPgpUserIdRevocationResult.Error>(revocation).reason,
        )
        assertEquals(
            NativeOpenPgpUserIdReplacementError.UNSUPPORTED_SIGNING_HASH,
            assertIs<NativeOpenPgpUserIdReplacementResult.Error>(replacement).reason,
        )
    }

    @Test
    fun replacementResultMapsPermanentPolicyConflict() {
        val replacement =
            OpenPgpUserIdReplacementResultProto(
                OpenPgpUserIdReplacementErrorOutcomeProto(
                    OpenPgpUserIdReplacementErrorProto(
                        OpenPgpUserIdReplacementErrorReasonProto.POLICY_CONFLICT,
                    ),
                ),
            ).toPublicUserIdReplacementResult(REPLACEMENT_OPERATION, FINGERPRINT)

        assertEquals(
            NativeOpenPgpUserIdReplacementError.POLICY_CONFLICT,
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
