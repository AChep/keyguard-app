package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeCryptoOpenPgpCertificateMaterialValidationTest {
    @Test
    fun certificateReconcileRejectsAmbiguousErrorCategories() {
        val response =
            OpenPgpCertificateMaterialReconcileResultProto(
                OpenPgpCertificateMaterialReconcileErrorOutcomeProto(
                    OpenPgpCertificateMaterialReconcileErrorProto(
                        existingPublicInputError =
                            OpenPgpCertificateMaterialInputErrorReasonProto.MALFORMED_CERTIFICATE,
                        pairError = OpenPgpCertificateMaterialPairErrorReasonProto.COMPONENT_COLLISION,
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicCertificateMaterialReconcileResult(
                    operation = RECONCILE_OPERATION,
                    expectedPrimaryFingerprint = FINGERPRINT,
                    privateOutputRequired = false,
                )
            }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
    }

    @Test
    fun certificateReconcileMapsConflictingSecretMaterial() {
        val response =
            OpenPgpCertificateMaterialReconcileResultProto(
                OpenPgpCertificateMaterialReconcileErrorOutcomeProto(
                    OpenPgpCertificateMaterialReconcileErrorProto(
                        pairError =
                            OpenPgpCertificateMaterialPairErrorReasonProto
                                .CONFLICTING_SECRET_MATERIAL,
                    ),
                ),
            )

        val result =
            response.toPublicCertificateMaterialReconcileResult(
                operation = RECONCILE_OPERATION,
                expectedPrimaryFingerprint = FINGERPRINT,
                privateOutputRequired = false,
            )
        val failure =
            assertIs<NativeOpenPgpCertificateMaterialReconcileFailure.Pair>(
                assertIs<NativeOpenPgpCertificateMaterialReconcileResult.Error>(result).failure,
            )

        assertEquals(
            NativeOpenPgpCertificateMaterialPairError.CONFLICTING_SECRET_MATERIAL,
            failure.reason,
        )
    }

    @Test
    fun certificateReconcileMapsUnsupportedTskLayoutToExactSecretInput() {
        val response =
            OpenPgpCertificateMaterialReconcileResultProto(
                OpenPgpCertificateMaterialReconcileErrorOutcomeProto(
                    OpenPgpCertificateMaterialReconcileErrorProto(
                        incomingSecretInputError =
                            OpenPgpCertificateMaterialInputErrorReasonProto.UNSUPPORTED_TSK_LAYOUT,
                    ),
                ),
            )

        val result =
            response.toPublicCertificateMaterialReconcileResult(
                operation = RECONCILE_OPERATION,
                expectedPrimaryFingerprint = FINGERPRINT,
                privateOutputRequired = true,
            )
        val failure =
            assertIs<NativeOpenPgpCertificateMaterialReconcileFailure.InvalidInputs>(
                assertIs<NativeOpenPgpCertificateMaterialReconcileResult.Error>(result).failure,
            )

        assertEquals(
            NativeOpenPgpCertificateMaterialInputError.UNSUPPORTED_TSK_LAYOUT,
            failure.incomingSecret,
        )
    }

    @Test
    fun certificateReconcileV2TransfersSeparatedOutputsAndContributions() {
        val localPublic = byteArrayOf(1)
        val localSecret = byteArrayOf(2)
        val transferablePublic = byteArrayOf(1)
        val transferableSecret = byteArrayOf(2)
        val response =
            OpenPgpCertificateMaterialReconcileV2ResultProto(
                OpenPgpCertificateMaterialReconcileV2SuccessOutcomeProto(
                    OpenPgpCertificateMaterialReconcileV2SuccessProto(
                        localPublicMaterial = localPublic,
                        localSecretMaterial = localSecret,
                        transferablePublicCertificate = transferablePublic,
                        transferableSecretKey = transferableSecret,
                        primaryFingerprint = FINGERPRINT,
                        contributions =
                            OpenPgpCertificateMaterialContributionsProto(
                                existingPublic = contribution(present = true),
                                incomingPublic = contribution(present = false),
                                existingSecret =
                                    contribution(
                                        present = true,
                                        uniqueSecretCapability = true,
                                    ),
                                incomingSecret = contribution(present = false),
                            ),
                    ),
                ),
            )

        val success =
            assertIs<NativeOpenPgpCertificateMaterialReconcileV2Result.Success>(
                response.toPublicCertificateMaterialReconcileV2Result(
                    operation = RECONCILE_V2_OPERATION,
                    expectedPrimaryFingerprint = FINGERPRINT,
                    expectedInputPresence = listOf(true, false, true, false),
                ),
            )

        assertTrue(success.localPublicMaterial === localPublic)
        assertTrue(success.localSecretMaterial === localSecret)
        assertTrue(success.transferablePublicCertificate === transferablePublic)
        assertTrue(success.transferableSecretKey === transferableSecret)
        assertTrue(success.contributions.existingSecret.uniqueSecretCapability)
        assertTrue(success.withheldReasons.isEmpty())
    }

    @Test
    fun certificateReconcileV2RejectsInconsistentWithholdingAndClearsEveryOutput() {
        val localPublic = byteArrayOf(1)
        val localSecret = byteArrayOf(2)
        val transferablePublic = byteArrayOf(3)
        val transferableSecret = byteArrayOf(4)
        val response =
            OpenPgpCertificateMaterialReconcileV2ResultProto(
                OpenPgpCertificateMaterialReconcileV2SuccessOutcomeProto(
                    OpenPgpCertificateMaterialReconcileV2SuccessProto(
                        localPublicMaterial = localPublic,
                        localSecretMaterial = localSecret,
                        transferablePublicCertificate = transferablePublic,
                        transferableSecretKey = transferableSecret,
                        primaryFingerprint = FINGERPRINT,
                        contributions =
                            OpenPgpCertificateMaterialContributionsProto(
                                existingPublic = contribution(present = true),
                                incomingPublic = contribution(present = false),
                                existingSecret = contribution(present = false),
                                incomingSecret = contribution(present = false),
                            ),
                        // Both local and transferable outputs differ, so omitting
                        // the matching reasons is a malformed native response.
                        withheldReasons = emptyList(),
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicCertificateMaterialReconcileV2Result(
                    operation = RECONCILE_V2_OPERATION,
                    expectedPrimaryFingerprint = FINGERPRINT,
                    expectedInputPresence = listOf(true, false, false, false),
                )
            }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(localPublic.all { it == 0.toByte() })
        assertTrue(localSecret.all { it == 0.toByte() })
        assertTrue(transferablePublic.all { it == 0.toByte() })
        assertTrue(transferableSecret.all { it == 0.toByte() })
    }

    @Test
    fun certificateReconcileV2RejectsSecretOutputForPublicOnlyInputsAndClearsEveryOutput() {
        val localPublic = byteArrayOf(1)
        val localSecret = byteArrayOf(2)
        val transferablePublic = byteArrayOf(1)
        val transferableSecret = byteArrayOf(2)
        val response =
            OpenPgpCertificateMaterialReconcileV2ResultProto(
                OpenPgpCertificateMaterialReconcileV2SuccessOutcomeProto(
                    OpenPgpCertificateMaterialReconcileV2SuccessProto(
                        localPublicMaterial = localPublic,
                        localSecretMaterial = localSecret,
                        transferablePublicCertificate = transferablePublic,
                        transferableSecretKey = transferableSecret,
                        primaryFingerprint = FINGERPRINT,
                        contributions =
                            OpenPgpCertificateMaterialContributionsProto(
                                existingPublic = contribution(present = true),
                                incomingPublic = contribution(present = false),
                                existingSecret = contribution(present = false),
                                incomingSecret = contribution(present = false),
                            ),
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicCertificateMaterialReconcileV2Result(
                    operation = RECONCILE_V2_OPERATION,
                    expectedPrimaryFingerprint = FINGERPRINT,
                    expectedInputPresence = listOf(true, false, false, false),
                )
            }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(localPublic.all { it == 0.toByte() })
        assertTrue(localSecret.all { it == 0.toByte() })
        assertTrue(transferablePublic.all { it == 0.toByte() })
        assertTrue(transferableSecret.all { it == 0.toByte() })
    }

    @Test
    fun certificateReconcileV2RejectsMissingLocalSecretForSecretInputAndClearsEveryOutput() {
        val localPublic = byteArrayOf(1)
        val transferablePublic = byteArrayOf(1)
        val response =
            OpenPgpCertificateMaterialReconcileV2ResultProto(
                OpenPgpCertificateMaterialReconcileV2SuccessOutcomeProto(
                    OpenPgpCertificateMaterialReconcileV2SuccessProto(
                        localPublicMaterial = localPublic,
                        transferablePublicCertificate = transferablePublic,
                        primaryFingerprint = FINGERPRINT,
                        contributions =
                            OpenPgpCertificateMaterialContributionsProto(
                                existingPublic = contribution(present = true),
                                incomingPublic = contribution(present = false),
                                existingSecret = contribution(present = true),
                                incomingSecret = contribution(present = false),
                            ),
                    ),
                ),
            )

        val failure =
            assertFailsWith<NativeCryptoException> {
                response.toPublicCertificateMaterialReconcileV2Result(
                    operation = RECONCILE_V2_OPERATION,
                    expectedPrimaryFingerprint = FINGERPRINT,
                    expectedInputPresence = listOf(true, false, true, false),
                )
            }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(localPublic.all { it == 0.toByte() })
        assertTrue(transferablePublic.all { it == 0.toByte() })
    }

    private fun contribution(
        present: Boolean,
        uniquePublicEvidence: Boolean = false,
        uniqueSecretCapability: Boolean = false,
    ) = OpenPgpCertificateMaterialInputContributionProto(
        present = present,
        uniquePublicEvidence = uniquePublicEvidence,
        uniqueSecretCapability = uniqueSecretCapability,
    )

    private companion object {
        const val RECONCILE_OPERATION = "open_pgp_certificate_material_reconcile"
        const val RECONCILE_V2_OPERATION = "open_pgp_certificate_material_reconcile_v2"
        const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
    }
}
