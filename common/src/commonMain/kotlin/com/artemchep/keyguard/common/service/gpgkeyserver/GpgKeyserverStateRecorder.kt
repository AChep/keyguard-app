package com.artemchep.keyguard.common.service.gpgkeyserver

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import kotlin.time.Instant

/** Records publication separately from the signed evidence deciding whether a key is revoked. */
internal class GpgKeyserverStateRecorder(
    private val repository: GpgKeyserverStateRepository,
    reconciler: GpgCertificateMaterialReconciler,
    resolver: GpgKeyMetadataResolver,
) {
    private val evaluator = GpgKeyserverStateEvaluator(reconciler, resolver)

    fun record(
        fingerprint: String,
        cipherIds: Set<String>,
        publicCertificates: List<String>,
        publicationStatus: GpgKeyserverVerificationStatus,
        sourceKeyserver: String,
        checkedAt: Instant,
        refreshed: Boolean,
        preserveVerified: Boolean = false,
    ): IO<DGpgKeyserverState> {
        require(publicationStatus in publicationStatuses)
        val normalized = fingerprint.normalizeGpgFingerprint()
        return repository.update(normalized) { current, localKeys ->
            val matchingKeys = localKeys.filter { it.fingerprint == normalized }
            val material = buildList {
                current?.revocationEvidenceArmored?.let(::add)
                addAll(matchingKeys.map { it.publicKeyArmored })
                addAll(publicCertificates)
            }
            val evidence = evaluator.mergeEvidence(normalized, material)

            // An old Boolean warning does not identify the revocation that caused it.
            // Retain new evidence, but never pretend it accounts for that missing history.
            val unbacked = current?.hasUnbackedRevocationEvidence() == true
            val publication = when {
                preserveVerified &&
                    publicationStatus == GpgKeyserverVerificationStatus.FOUND_UNVERIFIED &&
                    current?.publicationStatus == GpgKeyserverVerificationStatus.VERIFIED &&
                    current.sourceKeyserver == sourceKeyserver ->
                    GpgKeyserverVerificationStatus.VERIFIED
                else -> publicationStatus
            }
            val liveIds = matchingKeys.mapTo(mutableSetOf()) { it.cipherId }
            val state = DGpgKeyserverState(
                fingerprint = normalized,
                cipherId = current?.cipherId?.takeIf { it in liveIds }
                    ?: cipherIds.sorted().firstOrNull { it in liveIds },
                verificationStatus = current?.verificationStatus ?: GpgKeyserverVerificationStatus.UNKNOWN,
                publicationStatus = publication,
                lastCheckedAt = maxOf(checkedAt, current?.lastCheckedAt ?: checkedAt),
                lastRefreshedAt = if (refreshed) {
                    maxOf(checkedAt, current?.lastRefreshedAt ?: checkedAt)
                } else current?.lastRefreshedAt,
                sourceKeyserver = sourceKeyserver,
                revocationEvidenceArmored = evidence,
                hasUnbackedRevocation = unbacked,
            )
            state.copy(
                verificationStatus = evaluator.evaluate(
                    state = state,
                    evidence = evidence,
                    candidateRevocationKeys = localKeys
                        .map { it.publicKeyArmored }
                        .distinct()
                        .map(::GpgOpenPgpPublicKey),
                ),
            )
        }
    }

    private companion object {
        val publicationStatuses = setOf(
            GpgKeyserverVerificationStatus.NOT_FOUND,
            GpgKeyserverVerificationStatus.FOUND_UNVERIFIED,
            GpgKeyserverVerificationStatus.VERIFIED,
        )
    }
}
