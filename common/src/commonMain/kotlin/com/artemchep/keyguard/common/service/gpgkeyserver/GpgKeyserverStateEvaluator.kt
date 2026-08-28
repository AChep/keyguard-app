package com.artemchep.keyguard.common.service.gpgkeyserver

import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconcileResult
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgRevocationStatus
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import org.kodein.di.DirectDI
import org.kodein.di.instance

/** Evaluates retained public packets without accessing the network or modifying the vault. */
internal class GpgKeyserverStateEvaluator(
    private val reconciler: GpgCertificateMaterialReconciler,
    private val resolver: GpgKeyMetadataResolver,
) {
    constructor(directDI: DirectDI) : this(
        reconciler = directDI.instance(),
        resolver = directDI.instance(),
    )

    fun mergeEvidence(fingerprint: String, publicCertificates: List<String>): String? {
        val normalized = fingerprint.normalizeGpgFingerprint()
        var evidence: String? = null
        publicCertificates.distinct().forEach { incoming ->
            requireEvidenceSize(incoming)
            val merged = reconciler.reconcile(
                expectedPrimaryFingerprint = normalized,
                existingPublicCertificate = evidence,
                existingSecretCertificate = null,
                incomingPublicCertificate = incoming,
                incomingSecretCertificate = null,
            ) as? GpgCertificateMaterialReconcileResult.Success
                ?: error("Could not preserve GPG revocation evidence.")
            check(merged.primaryFingerprint == normalized)
            check(merged.localSecretMaterial == null && merged.transferableSecretKey == null)
            requireEvidenceSize(merged.localPublicMaterial)
            evidence = merged.localPublicMaterial
        }
        return evidence
    }

    fun evaluate(
        state: DGpgKeyserverState,
        evidence: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ): GpgKeyserverVerificationStatus {
        if (state.hasUnbackedRevocationEvidence()) {
            return GpgKeyserverVerificationStatus.REVOKED
        }
        val fingerprint = state.fingerprint.normalizeGpgFingerprint()
        val authorization = evidence?.let { publicKey ->
            resolver.resolve(
                privateKeyArmored = null,
                publicKeyArmored = publicKey,
                fingerprint = fingerprint,
                candidateRevocationKeys = candidateRevocationKeys,
            )?.authorization
        }
        return when (authorization?.takeIf { it.isSupported }?.revocations?.get(fingerprint)) {
            GpgRevocationStatus.REVOKED -> GpgKeyserverVerificationStatus.REVOKED
            GpgRevocationStatus.NOT_REVOKED -> state.publicationStatus
            else -> state.indeterminateVerificationStatus()
        }
    }

    private fun requireEvidenceSize(value: String) {
        // Never truncate: dropping a packet can change the meaning of a later evaluation.
        check(value.isNotBlank() && value.length <= MAX_EVIDENCE_BYTES)
        check(value.encodeToByteArray().size <= MAX_EVIDENCE_BYTES)
    }

    private companion object {
        const val MAX_EVIDENCE_BYTES = 4 * 1024 * 1024
    }
}

internal fun DGpgKeyserverState.hasUnbackedRevocationEvidence(): Boolean =
    hasUnbackedRevocation ||
        (verificationStatus == GpgKeyserverVerificationStatus.REVOKED && revocationEvidenceArmored == null)

internal fun DGpgKeyserverState.indeterminateVerificationStatus(): GpgKeyserverVerificationStatus =
    if (hasUnbackedRevocation || verificationStatus == GpgKeyserverVerificationStatus.REVOKED) {
        GpgKeyserverVerificationStatus.REVOKED
    } else {
        GpgKeyserverVerificationStatus.UNKNOWN
    }
