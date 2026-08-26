package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialOperationalError
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialPairError
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileFailure
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeGpgCertificateMaterialReconcilerTest {
    @Test
    fun `certificate material reconciliation returns a typed error for malformed fingerprints`() {
        listOf(
            "",
            "G".repeat(40),
            "A".repeat(41),
            "A".repeat(130),
        ).forEach { fingerprint ->
            val result =
                NativeGpgCertificateMaterialReconciler.reconcile(
                    expectedPrimaryFingerprint = fingerprint,
                    existingPublicCertificate = null,
                    existingSecretCertificate = null,
                    incomingPublicCertificate = null,
                    incomingSecretCertificate = null,
                )

            assertEquals(
                GpgCertificateMaterialPairError.FingerprintMismatch,
                assertIs<GpgCertificateMaterialReconcileFailure.Pair>(
                    assertIs<GpgCertificateMaterialReconcileResult.Error>(result).failure,
                ).reason,
                fingerprint,
            )
        }
    }

    @Test
    fun `certificate material reconciliation maps resource limits`() {
        val result =
            NativeGpgCertificateMaterialReconciler.reconcile(
                expectedPrimaryFingerprint = PRIMARY_FINGERPRINT,
                existingPublicCertificate = "A".repeat(NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES + 1),
                existingSecretCertificate = null,
                incomingPublicCertificate = PUBLIC_KEY,
                incomingSecretCertificate = null,
            )

        assertEquals(
            GpgCertificateMaterialOperationalError.ResourceLimit,
            assertIs<GpgCertificateMaterialReconcileFailure.Operational>(
                assertIs<GpgCertificateMaterialReconcileResult.Error>(result).failure,
            ).reason,
        )
    }

    @Test
    fun `certificate material reconciliation accepts duplicate public evidence`() {
        val result =
            assertIs<GpgCertificateMaterialReconcileResult.Success>(
                NativeGpgCertificateMaterialReconciler.reconcile(
                    expectedPrimaryFingerprint = PRIMARY_FINGERPRINT,
                    existingPublicCertificate = PUBLIC_KEY,
                    existingSecretCertificate = null,
                    incomingPublicCertificate = PUBLIC_KEY,
                    incomingSecretCertificate = null,
                ),
            )

        assertEquals(PRIMARY_FINGERPRINT, result.primaryFingerprint)
        assertNull(result.localSecretMaterial)
        assertNull(result.transferableSecretKey)
        assertTrue(result.localPublicMaterial.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        assertTrue(
            result.transferablePublicCertificate
                ?.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----") == true,
        )
        assertTrue(result.contributions.existingPublic.present)
        assertTrue(result.contributions.incomingPublic.present)
        assertTrue(!result.contributions.existingPublic.uniquePublicEvidence)
        assertTrue(!result.contributions.incomingPublic.uniquePublicEvidence)
    }

    private companion object {
        const val PRIMARY_FINGERPRINT = GPG_TEST_CV25519_PRIMARY_FINGERPRINT
        val PUBLIC_KEY = GPG_TEST_CV25519_PUBLIC_KEY
    }
}
