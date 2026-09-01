package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.util.io.CopyingRawSource
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.write

internal data class GpgOpenPgpDetachedVerification(
    val verification: GpgOpenPgpVerification,
    val bodySize: Long,
)

/**
 * The OpenPGP file operations expressed over selected key rings,
 * independent of any transport: callers hand in plain streams and get
 * domain results back.
 */
internal class GpgOpenPgpRingOperations(
    private val service: GpgOpenPgpService,
    private val certificateMaterialReconciler: GpgCertificateMaterialReconciler,
) {
    fun exportPublicKey(
        ring: GpgOpenPgpRing,
        output: Sink,
        armored: Boolean,
    ) {
        service.exportPublicKey(
            GpgOpenPgpExportPublicKeyRequest(
                publicKey = ring.publicKey(),
                output = output,
                armored = armored,
            ),
        )
    }

    fun clearSign(
        privateKey: GpgOpenPgpPrivateKey,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
        input: Source,
        output: Sink,
    ) {
        service.clearSignFile(
            GpgOpenPgpClearSignFileRequest(
                input = input,
                output = output,
                privateKey = privateKey,
                candidateRevocationKeys = candidateRevocationKeys,
            ),
        )
    }

    fun detachedSign(
        privateKey: GpgOpenPgpPrivateKey,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
        input: Source,
        armored: Boolean,
    ): ByteArray {
        val signatureOutput = Buffer()
        service.signFile(
            GpgOpenPgpSignFileRequest(
                input = input,
                signatureOutput = signatureOutput,
                privateKey = privateKey,
                candidateRevocationKeys = candidateRevocationKeys,
                armored = armored,
            ),
        )
        return signatureOutput.readByteArray()
    }

    @Suppress("LongParameterList")
    fun encrypt(
        recipients: List<GpgOpenPgpRing>,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
        signingPrivateKey: GpgOpenPgpPrivateKey?,
        input: Source,
        output: Sink,
        fileName: String?,
        armored: Boolean,
        enableCompression: Boolean,
    ) {
        service.encryptFile(
            GpgOpenPgpEncryptFileRequest(
                input = input,
                output = output,
                publicKeys = recipients.map { it.publicKey() },
                candidateRevocationKeys = candidateRevocationKeys,
                fileName = GpgOpenPgpLiteralFileName.fromUntrusted(fileName),
                armored = armored,
                signingPrivateKey = signingPrivateKey,
                enableCompression = enableCompression,
            ),
        )
    }

    fun read(
        keys: GpgOpenPgpReadKeyScope,
        input: Source,
        output: Sink,
    ): GpgOpenPgpReadFileResult {
        val result = service.readFile(
            GpgOpenPgpReadFileRequest(
                input = input,
                output = output,
                privateKeys = keys.decryptionKeys,
                publicKeys = keys.verificationKeys,
                allowSignedOnly = true,
            ),
        )
        val confirmation = ConfirmationScope(keys.confirmationEvidence)
        return when (result) {
            is GpgOpenPgpReadFileResult.Message -> result.copy(
                verification = result.verification?.withConfirmedUserIds(confirmation),
            )

            is GpgOpenPgpReadFileResult.ClearSigned -> result.copy(
                verification = result.verification.withConfirmedUserIds(confirmation),
            )
        }
    }

    fun verifyDetached(
        keys: GpgOpenPgpReadKeyScope,
        input: Source,
        output: Sink,
        signature: ByteArray,
    ): GpgOpenPgpDetachedVerification {
        val copyingSource = CopyingRawSource(
            input = input,
            output = output,
        )
        val verification = service.verifyFile(
            GpgOpenPgpVerifyFileRequest(
                input = copyingSource.buffered(),
                signatureInput = Buffer().apply {
                    write(signature)
                },
                publicKeys = keys.verificationKeys,
            ),
        )
        output.flush()
        return GpgOpenPgpDetachedVerification(
            verification = verification.withConfirmedUserIds(
                ConfirmationScope(keys.confirmationEvidence),
            ),
            bodySize = copyingSource.size,
        )
    }

    private fun GpgOpenPgpVerification.withConfirmedUserIds(
        confirmation: ConfirmationScope,
    ): GpgOpenPgpVerification = copy(
        // A policy conflict makes every identity assertion for this signer
        // ambiguous, so a conflicted result never carries confirmed identities.
        confirmedUserIds = if (GpgOpenPgpVerificationWarning.POLICY_CONFLICT in warnings) {
            emptyList()
        } else {
            confirmation.confirmedUserIdsFor(fingerprint)
                .filter { userId -> userId in userIds }
                .distinct()
        },
        signatures = signatures.map { result ->
            result.withConfirmedUserIds(confirmation)
        },
    )

    /**
     * Signer confirmation for one read: the fingerprint indexes and the
     * per-signer results are computed at most once per request, however many
     * signatures the message carries, and not at all for an unsigned message.
     */
    private inner class ConfirmationScope(
        private val evidence: GpgOpenPgpReadKeyScope.ConfirmationEvidence,
    ) {
        private val revisionsByPrimary by lazy {
            evidence.revisions
                .groupBy(GpgOpenPgpReadKeyScope.CertificateRevision::primaryFingerprint)
        }

        /** Normalized primary fingerprints per normalized component fingerprint. */
        private val primariesByComponent: Map<String, Set<String>> by lazy {
            evidence.revisions
                .flatMap { revision ->
                    revision.componentFingerprints
                        .map { componentFingerprint ->
                            componentFingerprint to revision.primaryFingerprint
                        }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, primaries) -> primaries.toSet() }
        }

        private val cache = mutableMapOf<String, List<String>>()

        /**
         * Any ambiguity fails closed: a component fingerprint that resolves
         * to more than one primary confirms nothing.
         */
        fun confirmedUserIdsFor(componentFingerprint: String?): List<String> {
            if (evidence.certificationAuthorities.isEmpty()) {
                return emptyList()
            }
            val primaryFingerprint = componentFingerprint
                ?.normalizeGpgFingerprint()
                ?.let { fingerprint -> primariesByComponent[fingerprint] }
                ?.singleOrNull()
                ?: return emptyList()
            return cache.getOrPut(primaryFingerprint) {
                confirmedUserIds(primaryFingerprint)
            }
        }

        private fun confirmedUserIds(primaryFingerprint: String): List<String> {
            val revisions = revisionsByPrimary[primaryFingerprint].orEmpty()
            val revisionKeys = revisions
                .map { revision -> revision.publicKey.armored }
                .distinct()
                .sorted()
            return runCatchingNonFatal {
                val merged = mergePublicCertificate(primaryFingerprint, revisionKeys)
                    ?: return@runCatchingNonFatal emptyList()
                val referenceTime = revisions.maxOf { it.referenceTime }
                (revisionKeys + merged)
                    .distinct()
                    .sorted()
                    .map { publicKeyArmored ->
                        service.evaluateUserIdCertifications(
                            GpgOpenPgpUserIdCertificationRequest(
                                publicKey = GpgOpenPgpPublicKey(publicKeyArmored),
                                authorities = evidence.certificationAuthorities,
                                referenceTime = referenceTime,
                            ),
                        ).toSet()
                    }
                    .reduceOrNull { confirmed, revisionConfirmed ->
                        confirmed intersect revisionConfirmed
                    }
                    ?.sorted()
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Builds one additive policy view for the distinct, sorted armored
     * [revisions] of a certificate. Sorting the inputs keeps the result
     * independent of repository order; any ambiguity or reconciliation
     * failure leaves the signer unconfirmed. The caller additionally requires
     * every individual revision to agree with this merged view, so omitted
     * evidence cannot preserve a stale confirmation.
     */
    private fun mergePublicCertificate(
        primaryFingerprint: String,
        revisions: List<String>,
    ): String? {
        var merged = revisions.firstOrNull() ?: return null
        for (revision in revisions.drop(1)) {
            merged = certificateMaterialReconciler.reconcile(
                expectedPrimaryFingerprint = primaryFingerprint,
                existingPublicCertificate = merged,
                existingSecretCertificate = null,
                incomingPublicCertificate = revision,
                incomingSecretCertificate = null,
            ).validSuccessOrNull(primaryFingerprint)
                ?.localPublicMaterial
                ?: return null
        }
        return merged
    }
}
