package com.artemchep.keyguard.common.service.crypto

/**
 * Reconciles packet-preserving public evidence and secret components from two
 * logical certificate sides into coherent persisted material.
 */
interface GpgCertificateMaterialReconciler {
    fun reconcile(
        expectedPrimaryFingerprint: String,
        existingPublicCertificate: String?,
        existingSecretCertificate: String?,
        incomingPublicCertificate: String?,
        incomingSecretCertificate: String?,
    ): GpgCertificateMaterialReconcileResult
}
