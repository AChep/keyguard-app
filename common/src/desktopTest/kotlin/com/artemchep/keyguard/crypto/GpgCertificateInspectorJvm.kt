package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.util.toHex
import org.bouncycastle.bcpg.KeyIdentifier
import org.bouncycastle.bcpg.SignatureSubpacketTags
import org.bouncycastle.bcpg.sig.RevocationKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPUserAttributeSubpacketVector
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A cryptographically authenticated view of a transferable OpenPGP certificate.
 *
 * The underlying ring is deliberately left untouched: third-party certifications and even
 * malformed or forged packets remain transferable. Consumers must derive policy facts from this
 * view instead of Bouncy Castle's packet-presence helpers, which do not authenticate signatures.
 */
internal class GpgCertificateInspectorJvm private constructor(
    val ring: PGPPublicKeyRing,
    private val primaryKey: PGPPublicKey,
    private val candidateRevocationKeys: List<PGPPublicKey>,
    private val referenceTime: Instant,
) {
    val verifiedUserIds: List<String> by lazy {
        primaryKey.rawUserIDs
            .asSequence()
            .filter { rawUserId ->
                effectiveUserIdCertification(rawUserId) != null &&
                    verifiedUserIdRevocations(rawUserId).isEmpty()
            }
            .map(ByteArray::decodeToString)
            .toList()
    }

    val primary: GpgVerifiedCertificateKeyJvm by lazy {
        val effectiveSignature = effectivePrimarySelfSignature()
        val revocationAssessment = primaryRevocationAssessment()
        GpgVerifiedCertificateKeyJvm(
            publicKey = primaryKey,
            authenticated = effectiveSignature != null,
            effectiveSignature = effectiveSignature,
            keyFlags = effectiveSignature.authenticatedKeyFlagsOrNull(),
            validSeconds = effectiveSignature.authenticatedKeyExpirationTime(),
            revocationStatus = revocationAssessment.status,
            signingCrossCertified = true,
        )
    }

    val subkeys: List<GpgVerifiedCertificateKeyJvm> by lazy {
        ring.publicKeys
            .asSequence()
            .filterNot { key -> key.fingerprint.contentEquals(primaryKey.fingerprint) }
            .map { subkey ->
                val binding = effectiveSubkeyBinding(subkey)
                val revocationAssessment = subkeyRevocationAssessment(subkey)
                GpgVerifiedCertificateKeyJvm(
                    publicKey = subkey,
                    authenticated = binding != null,
                    effectiveSignature = binding,
                    keyFlags = binding.authenticatedKeyFlagsOrNull(),
                    validSeconds = binding.authenticatedKeyExpirationTime(),
                    revocationStatus = revocationAssessment.status,
                    signingCrossCertified = binding?.hasVerifiedPrimaryKeyBinding(subkey) == true,
                )
            }
            .toList()
    }

    val keys: List<GpgVerifiedCertificateKeyJvm>
        get() = listOf(primary) + subkeys

    val authenticatedKeys: List<GpgVerifiedCertificateKeyJvm>
        get() = keys.filter { it.authenticated }

    fun verifiedDirectKeySignatures(): List<PGPSignature> = primaryKey.keySignatures
        .safeSequence()
        .filter { signature -> signature.signatureType == PGPSignature.DIRECT_KEY }
        .filter { signature -> signature.verifiesDirectKeyCertification(primaryKey) }
        .toList()

    // GnuPG still reads designated-revoker declarations from expired direct signatures, so the
    // crypto-only list above stays unfiltered and policy consumers use this temporal view.
    fun effectiveDirectKeySignatures(): List<PGPSignature> =
        verifiedDirectKeySignatures().filterNot { signature ->
            signature.isExpiredAt(referenceTime)
        }

    fun effectiveDirectKeySignature(): PGPSignature? =
        effectiveDirectKeySignatures().newestAuthenticatedSignature()

    fun verifiedPrimaryRevocations(): List<PGPSignature> =
        primaryRevocationAssessment().verifiedSignatures

    fun primaryRevocationStatus(): GpgRevocationStatusJvm =
        primaryRevocationAssessment().status

    fun verifiedUserIdCertifications(
        rawUserId: ByteArray,
    ): List<PGPSignature> = primaryKey.getSignaturesForID(rawUserId)
        .safeSequence()
        .filter { signature -> signature.signatureType in IDENTITY_CERTIFICATIONS }
        .filter { signature -> signature.verifiesUserIdCertification(rawUserId, primaryKey) }
        .toList()

    fun effectiveUserIdCertification(
        rawUserId: ByteArray,
    ): PGPSignature? = verifiedUserIdCertifications(rawUserId)
        // Unlike direct signatures and bindings, GnuPG chooses the newest UID self-signature
        // first and then expires that identity; it does not revive an older live certification.
        .newestAuthenticatedSignature()
        ?.takeUnless { signature -> signature.isExpiredAt(referenceTime) }

    fun verifiedUserIdRevocations(
        rawUserId: ByteArray,
    ): List<PGPSignature> = userIdRevocationAssessment(rawUserId).verifiedSignatures

    fun userIdRevocationStatus(
        rawUserId: ByteArray,
    ): GpgRevocationStatusJvm = userIdRevocationAssessment(rawUserId).status

    fun verifiedUserAttributeCertifications(
        attribute: PGPUserAttributeSubpacketVector,
    ): List<PGPSignature> = primaryKey.getSignaturesForUserAttribute(attribute)
        .safeSequence()
        .filter { signature -> signature.signatureType in IDENTITY_CERTIFICATIONS }
        .filter { signature -> signature.verifiesUserAttributeCertification(attribute, primaryKey) }
        .toList()

    fun effectiveUserAttributeCertification(
        attribute: PGPUserAttributeSubpacketVector,
    ): PGPSignature? = verifiedUserAttributeCertifications(attribute)
        .newestAuthenticatedSignature()
        ?.takeUnless { signature -> signature.isExpiredAt(referenceTime) }

    fun verifiedUserAttributeRevocations(
        attribute: PGPUserAttributeSubpacketVector,
    ): List<PGPSignature> = userAttributeRevocationAssessment(attribute).verifiedSignatures

    fun userAttributeRevocationStatus(
        attribute: PGPUserAttributeSubpacketVector,
    ): GpgRevocationStatusJvm = userAttributeRevocationAssessment(attribute).status

    fun verifiedSubkeyBindings(
        subkey: PGPPublicKey,
    ): List<PGPSignature> = subkey.signatures
        .safeSequence()
        .filter { signature -> signature.signatureType == PGPSignature.SUBKEY_BINDING }
        .filter { signature -> signature.verifiesSubkeyCertification(primaryKey, subkey) }
        .toList()

    fun effectiveSubkeyBindings(
        subkey: PGPPublicKey,
    ): List<PGPSignature> = verifiedSubkeyBindings(subkey).filterNot { signature ->
        // GnuPG ignores an expired binding and can fall back to an older live binding.
        signature.isExpiredAt(referenceTime)
    }

    fun effectiveSubkeyBinding(
        subkey: PGPPublicKey,
    ): PGPSignature? = effectiveSubkeyBindings(subkey).newestAuthenticatedSignature()

    fun verifiedSubkeyRevocations(
        subkey: PGPPublicKey,
    ): List<PGPSignature> = subkeyRevocationAssessment(subkey).verifiedSignatures

    fun subkeyRevocationStatus(
        subkey: PGPPublicKey,
    ): GpgRevocationStatusJvm = subkeyRevocationAssessment(subkey).status

    fun hasUnresolvedIdentityRevocations(): Boolean =
        primaryKey.rawUserIDs.asSequence().any { rawUserId ->
            userIdRevocationStatus(rawUserId) is GpgRevocationStatusJvm.Unresolved
        } || primaryKey.userAttributes.asSequence().any { attribute ->
            userAttributeRevocationStatus(attribute) is GpgRevocationStatusJvm.Unresolved
        }

    /**
     * Concrete keys authorized by Revocation Key subpackets in cryptographically authenticated,
     * hashed direct-key self-signatures.
     *
     * Issuer subpackets on a revocation are forgeable lookup hints. They are consulted only to
     * report an unresolved authority when its declared key is unavailable; they never establish
     * that a revocation is authentic.
     */
    private val authenticatedRevocationKeyDeclarations: List<RevocationKey> by lazy {
        verifiedDirectKeySignatures()
            .hashedRevocationKeys()
            .filter { declaration -> declaration.isAuthorizedRevocationKey() }
    }

    @Suppress("DEPRECATION")
    private val verifiedRevocationKeys: List<PGPPublicKey> by lazy {
        candidateRevocationKeys.filter { candidate ->
            authenticatedRevocationKeyDeclarations.any { declaration ->
                declaration.matches(candidate)
            }
        }
    }

    private val unavailableRevocationKeyDeclarations: List<RevocationKey> by lazy {
        authenticatedRevocationKeyDeclarations.filterNot { declaration ->
            candidateRevocationKeys.any { candidate -> declaration.matches(candidate) }
        }
    }

    private fun primaryRevocationAssessment(): GpgRevocationAssessmentJvm =
        assessRevocations(
            signatures = primaryKey.keySignatures
                .safeSequence()
                .filter { signature -> signature.signatureType == PGPSignature.KEY_REVOCATION },
        ) { signature, signer ->
            signature.verifiesDirectKeyCertification(
                primary = primaryKey,
                signer = signer,
            )
        }

    private fun userIdRevocationAssessment(
        rawUserId: ByteArray,
    ): GpgRevocationAssessmentJvm = assessRevocations(
        signatures = primaryKey.getSignaturesForID(rawUserId)
            .safeSequence()
            .filter { signature -> signature.signatureType == PGPSignature.CERTIFICATION_REVOCATION },
    ) { signature, signer ->
        signature.verifiesUserIdCertification(
            rawUserId = rawUserId,
            primary = primaryKey,
            signer = signer,
        )
    }

    private fun userAttributeRevocationAssessment(
        attribute: PGPUserAttributeSubpacketVector,
    ): GpgRevocationAssessmentJvm = assessRevocations(
        signatures = primaryKey.getSignaturesForUserAttribute(attribute)
            .safeSequence()
            .filter { signature -> signature.signatureType == PGPSignature.CERTIFICATION_REVOCATION },
    ) { signature, signer ->
        signature.verifiesUserAttributeCertification(
            attribute = attribute,
            primary = primaryKey,
            signer = signer,
        )
    }

    private fun subkeyRevocationAssessment(
        subkey: PGPPublicKey,
    ): GpgRevocationAssessmentJvm = assessRevocations(
        signatures = subkey.signatures
            .safeSequence()
            .filter { signature -> signature.signatureType == PGPSignature.SUBKEY_REVOCATION },
    ) { signature, signer ->
        signature.verifiesSubkeyCertification(
            primary = primaryKey,
            subkey = subkey,
            signer = signer,
        )
    }

    private fun assessRevocations(
        signatures: Sequence<PGPSignature>,
        verifiesWith: (PGPSignature, PGPPublicKey) -> Boolean,
    ): GpgRevocationAssessmentJvm {
        val relevantSignatures = signatures.toList()
        val verifiedSignatures = relevantSignatures.filter { signature ->
            verifiesWith(signature, primaryKey) ||
                verifiedRevocationKeys.any { revocationKey ->
                    verifiesWith(signature, revocationKey)
                }
        }
        if (verifiedSignatures.isNotEmpty()) {
            return GpgRevocationAssessmentJvm(
                verifiedSignatures = verifiedSignatures,
                unresolvedAuthorities = emptySet(),
            )
        }

        // Issuer identifiers are unauthenticated lookup hints. They can justify a conservative
        // "unresolved" result while the declared authority is unavailable, but never revocation.
        val unresolvedAuthorities = relevantSignatures
            .asSequence()
            .flatMap { signature ->
                unavailableRevocationKeyDeclarations
                    .asSequence()
                    .filter { declaration -> signature.matchesIssuerHint(declaration) }
                    .map(RevocationKey::toAuthority)
            }
            .toSet()
        return GpgRevocationAssessmentJvm(
            verifiedSignatures = emptyList(),
            unresolvedAuthorities = unresolvedAuthorities,
        )
    }

    private fun effectivePrimarySelfSignature(): PGPSignature? = buildList {
        effectiveDirectKeySignature()?.let(::add)
        primaryKey.rawUserIDs.asSequence().forEach { rawUserId ->
            if (verifiedUserIdRevocations(rawUserId).isEmpty()) {
                effectiveUserIdCertification(rawUserId)?.let(::add)
            }
        }
        primaryKey.userAttributes.asSequence().forEach { attribute ->
            if (verifiedUserAttributeRevocations(attribute).isEmpty()) {
                effectiveUserAttributeCertification(attribute)?.let(::add)
            }
        }
    }.newestAuthenticatedSignature()

    private fun PGPSignature.hasVerifiedPrimaryKeyBinding(
        subkey: PGPPublicKey,
    ): Boolean = runCatching {
        hashedSubPackets?.embeddedSignatures
            ?.asSequence()
            ?.filter { signature ->
                signature.signatureType == PGPSignature.PRIMARYKEY_BINDING
            }
            ?.any { signature ->
                signature.verifiesSubkeyCertification(
                    primary = primaryKey,
                    subkey = subkey,
                    signer = subkey,
                )
            } == true
    }.getOrDefault(false)

    companion object {
        fun inspect(
            ring: PGPPublicKeyRing,
            referenceTime: Instant = Clock.System.now(),
        ): GpgCertificateInspectorJvm? = inspect(
            ring = ring,
            candidateRevocationKeys = emptyList(),
            referenceTime = referenceTime,
        )

        fun inspect(
            ring: PGPPublicKeyRing,
            candidateRevocationKeys: Iterable<PGPPublicKey>,
            referenceTime: Instant = Clock.System.now(),
        ): GpgCertificateInspectorJvm? {
            val keys = ring.publicKeys
                .asSequence()
                .toList()
            if (keys.any { key -> !key.isSupportedGpgKeyVersion() }) {
                return null
            }
            val primary = keys
                .asSequence()
                .firstOrNull { it.isMasterKey }
                ?: return null
            return GpgCertificateInspectorJvm(
                ring = ring,
                primaryKey = primary,
                candidateRevocationKeys = buildList {
                    addAll(keys)
                    candidateRevocationKeys
                        .filter(PGPPublicKey::isSupportedGpgKeyVersion)
                        .forEach(::add)
                },
                referenceTime = referenceTime,
            )
        }
    }
}

internal data class GpgVerifiedCertificateKeyJvm(
    val publicKey: PGPPublicKey,
    val authenticated: Boolean,
    val effectiveSignature: PGPSignature?,
    val keyFlags: Int?,
    val validSeconds: Long,
    val revocationStatus: GpgRevocationStatusJvm,
    val signingCrossCertified: Boolean,
) {
    val revoked: Boolean
        get() = revocationStatus === GpgRevocationStatusJvm.Revoked
}

internal sealed interface GpgRevocationStatusJvm {
    data object NotRevoked : GpgRevocationStatusJvm

    data object Revoked : GpgRevocationStatusJvm

    data class Unresolved(
        val authorities: Set<GpgRevocationAuthorityJvm>,
    ) : GpgRevocationStatusJvm
}

internal data class GpgRevocationAuthorityJvm(
    val algorithm: Int,
    val fingerprint: String,
)

private data class GpgRevocationAssessmentJvm(
    val verifiedSignatures: List<PGPSignature>,
    val unresolvedAuthorities: Set<GpgRevocationAuthorityJvm>,
) {
    val status: GpgRevocationStatusJvm
        get() = when {
            verifiedSignatures.isNotEmpty() -> GpgRevocationStatusJvm.Revoked
            unresolvedAuthorities.isNotEmpty() -> GpgRevocationStatusJvm.Unresolved(
                unresolvedAuthorities,
            )

            else -> GpgRevocationStatusJvm.NotRevoked
        }
}

internal fun PGPSignature.verifiesDirectKeyCertification(
    primary: PGPPublicKey,
    signer: PGPPublicKey = primary,
): Boolean = verifiesCertificationWith(signer) {
    verifyCertification(primary)
}

internal fun PGPSignature.verifiesUserIdCertification(
    rawUserId: ByteArray,
    primary: PGPPublicKey,
    signer: PGPPublicKey = primary,
): Boolean = verifiesCertificationWith(signer) {
    verifyCertification(rawUserId, primary)
}

internal fun PGPSignature.verifiesUserAttributeCertification(
    attribute: PGPUserAttributeSubpacketVector,
    primary: PGPPublicKey,
    signer: PGPPublicKey = primary,
): Boolean = verifiesCertificationWith(signer) {
    verifyCertification(attribute, primary)
}

internal fun PGPSignature.verifiesSubkeyCertification(
    primary: PGPPublicKey,
    subkey: PGPPublicKey,
    signer: PGPPublicKey = primary,
): Boolean = verifiesCertificationWith(signer) {
    verifyCertification(primary, subkey)
}

private fun PGPSignature.verifiesCertificationWith(
    signer: PGPPublicKey,
    verification: PGPSignature.() -> Boolean,
): Boolean = runCatching {
    init(
        JcaPGPContentVerifierBuilderProvider()
            .setProvider(gpgBouncyCastleProvider),
        signer,
    )
    verification(this)
}.getOrDefault(false)

private fun PGPSignature?.authenticatedKeyFlagsOrNull(): Int? {
    val packets = this?.hashedSubPackets ?: return null
    if (!packets.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS)) {
        return null
    }
    return packets.keyFlags
}

private fun PGPSignature?.authenticatedKeyExpirationTime(): Long =
    this?.hashedSubPackets?.keyExpirationTime ?: 0L

private fun List<PGPSignature>.newestAuthenticatedSignature(): PGPSignature? =
    maxByOrNull(PGPSignature::authenticatedCreationTimeMillis)

private fun PGPSignature.authenticatedCreationTimeMillis(): Long = when (version) {
    3 -> creationTime.time
    else -> hashedSubPackets?.signatureCreationTime?.time ?: Long.MIN_VALUE
}

/** Matches GnuPG's hashed SIG_EXPIRE handling in parse-packet.c. */
internal fun PGPSignature.isExpiredAt(
    referenceTime: Instant,
): Boolean {
    val durationSeconds = hashedSubPackets?.signatureExpirationTime
        ?.and(UINT32_MASK)
        ?: return false
    if (durationSeconds == 0L) {
        return false
    }
    val creationSeconds = when (version) {
        3 -> creationTime.time / 1_000L
        else -> hashedSubPackets?.signatureCreationTime?.time?.div(1_000L) ?: 0L
    }.and(UINT32_MASK)
    val expirationSeconds = (creationSeconds + durationSeconds).and(UINT32_MASK)
    return expirationSeconds != 0L &&
        expirationSeconds <= referenceTime.epochSeconds.and(UINT32_MASK)
}

private fun <T> Iterator<T>?.safeSequence(): Sequence<T> =
    this?.asSequence() ?: emptySequence()

@Suppress("DEPRECATION")
private fun List<PGPSignature>.hashedRevocationKeys(): List<RevocationKey> = flatMap { signature ->
    signature.hashedSubPackets?.revocationKeys?.asList().orEmpty()
}

@Suppress("DEPRECATION")
private fun RevocationKey.isAuthorizedRevocationKey(): Boolean =
    signatureClass.toInt() and REVOCATION_AUTHORIZATION_FLAG != 0

@Suppress("DEPRECATION")
private fun RevocationKey.matches(
    candidate: PGPPublicKey,
): Boolean = algorithm == candidate.algorithm &&
    fingerprint.contentEquals(candidate.fingerprint)

@Suppress("DEPRECATION")
private fun PGPSignature.matchesIssuerHint(
    declaration: RevocationKey,
): Boolean {
    if (keyAlgorithm != declaration.algorithm) {
        return false
    }
    val identifiers = keyIdentifiers.filterNot(KeyIdentifier::isWildcard)
    if (identifiers.isEmpty()) {
        // Issuer identifiers are optional. With the declared key unavailable, a correctly typed
        // same-algorithm revocation cannot be disproved and must remain unresolved.
        return true
    }
    val declaredKeyId = KeyIdentifier(declaration.fingerprint).keyId
    return identifiers.any { identifier ->
        val fingerprint = identifier.fingerprint?.takeIf(ByteArray::isNotEmpty)
        if (fingerprint != null) {
            fingerprint.contentEquals(declaration.fingerprint)
        } else {
            declaredKeyId != 0L && identifier.keyId == declaredKeyId
        }
    }
}

@Suppress("DEPRECATION")
private fun RevocationKey.toAuthority(): GpgRevocationAuthorityJvm =
    GpgRevocationAuthorityJvm(
        algorithm = algorithm,
        fingerprint = fingerprint.toHex().uppercase(),
    )

private const val REVOCATION_AUTHORIZATION_FLAG = 0x80
private const val UINT32_MASK = 0xFFFF_FFFFL

private val IDENTITY_CERTIFICATIONS = setOf(
    PGPSignature.DEFAULT_CERTIFICATION,
    PGPSignature.NO_CERTIFICATION,
    PGPSignature.CASUAL_CERTIFICATION,
    PGPSignature.POSITIVE_CERTIFICATION,
)
