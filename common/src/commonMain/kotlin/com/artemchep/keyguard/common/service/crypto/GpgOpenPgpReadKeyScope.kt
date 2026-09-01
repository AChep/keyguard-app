package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import kotlin.time.Instant

/**
 * Key capabilities and trust evidence captured for one read operation.
 *
 * Operation keys come only from the approved selection. Confirmation evidence
 * comes from the complete vault snapshot and contains no private key material.
 */
internal class GpgOpenPgpReadKeyScope private constructor(
    val decryptionKeys: List<GpgOpenPgpPrivateKey>,
    val verificationKeys: List<GpgOpenPgpPublicKey>,
    val confirmationEvidence: ConfirmationEvidence,
) {
    data class ConfirmationEvidence(
        val revisions: List<CertificateRevision>,
        val certificationAuthorities: List<GpgOpenPgpCertificationAuthority>,
    )

    data class CertificateRevision(
        val primaryFingerprint: String,
        val componentFingerprints: Set<String>,
        val publicKey: GpgOpenPgpPublicKey,
        val referenceTime: Instant,
    )

    companion object {
        fun from(
            vault: GpgOpenPgpVault,
            operationRings: List<GpgOpenPgpRing>,
        ): GpgOpenPgpReadKeyScope {
            val ringsByIdentity = vault.rings.associateBy(GpgOpenPgpRing::identity)
            require(ringsByIdentity.size == vault.rings.size) {
                "The OpenPGP vault contains duplicate ring identities."
            }
            val operationIdentities = operationRings.map(GpgOpenPgpRing::identity)
            require(operationIdentities.distinct().size == operationIdentities.size) {
                "The OpenPGP operation contains duplicate ring identities."
            }
            val selectedRings = operationIdentities.map { identity ->
                requireNotNull(ringsByIdentity[identity]) {
                    "An OpenPGP operation ring does not belong to the vault snapshot."
                }
            }
            return GpgOpenPgpReadKeyScope(
                decryptionKeys = selectedRings
                    .filter(GpgOpenPgpRing::canDecrypt)
                    .mapNotNull(GpgOpenPgpRing::privateKey),
                verificationKeys = selectedRings.map(GpgOpenPgpRing::publicKey),
                // Without authorities nothing can be confirmed, so skip
                // building the revision evidence for the whole vault.
                confirmationEvidence = if (vault.certificationAuthorities.isEmpty()) {
                    ConfirmationEvidence(
                        revisions = emptyList(),
                        certificationAuthorities = emptyList(),
                    )
                } else {
                    ConfirmationEvidence(
                        revisions = vault.rings.map { ring ->
                            val primaryFingerprint = ring.info.fingerprint
                                .normalizeGpgFingerprint()
                            CertificateRevision(
                                primaryFingerprint = primaryFingerprint,
                                componentFingerprints = buildSet {
                                    add(primaryFingerprint)
                                    ring.info.subKeys.forEach { subKey ->
                                        add(subKey.fingerprint.normalizeGpgFingerprint())
                                    }
                                },
                                publicKey = ring.publicKey(),
                                referenceTime = ring.now,
                            )
                        },
                        certificationAuthorities = vault.certificationAuthorities,
                    )
                },
            )
        }
    }
}

private data class GpgOpenPgpRingIdentity(
    val accountId: String,
    val cipherId: String,
)

private val GpgOpenPgpRing.identity: GpgOpenPgpRingIdentity
    get() = GpgOpenPgpRingIdentity(
        accountId = accountId,
        cipherId = cipherId,
    )
