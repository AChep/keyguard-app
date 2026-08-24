package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgp
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateResolution
import kotlin.time.Clock

internal inline fun <T> List<GpgOpenPgpPublicKey>.withEncodedPublicKeys(
    block: (List<ByteArray>) -> T,
): T {
    val keyData = clampToNativeOpenPgpKeyLimit()
        .map { key -> key.armored.encodeToByteArray() }
    return try {
        block(keyData)
    } finally {
        keyData.eraseAll()
    }
}

/**
 * Narrows a vault-sized candidate list using native-authenticated V2 certificate indexes.
 * The final native operation remains authoritative and receives only candidates that can satisfy
 * a declared legacy revocation authority. Revoker relationships remain transient in this scope.
 */
internal fun <T> List<GpgOpenPgpPublicKey>.withEncodedRevocationKeyCandidates(
    targetPrivateKeys: List<EncodedRevocationTarget> = emptyList(),
    targetPublicKeys: List<EncodedRevocationTarget> = emptyList(),
    referenceTimeEpochSeconds: Long = Clock.System.now().epochSeconds,
    block: (
        candidateRevocationKeys: List<ByteArray>,
        referenceTimeEpochSeconds: Long,
    ) -> T,
): T = selectRevocationKeyCandidates(
    targetPrivateKeys = targetPrivateKeys,
    targetPublicKeys = targetPublicKeys,
    candidates = this,
    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
).withEncodedPublicKeys { keyData ->
    // The clamp inside withEncodedPublicKeys is a no-op here: every selection
    // path already enforces MAX_KEY_DOCUMENTS_PER_REQUEST via
    // requireNativeOpenPgpRevocationCandidateLimit.
    block(keyData, referenceTimeEpochSeconds)
}

internal data class EncodedRevocationTarget(
    val keyData: ByteArray,
    val preferredFingerprint: String = "",
)

internal data class RevocationAuthorityId(
    val publicKeyAlgorithmId: Int,
    val fingerprint: String,
)

internal class RevocationCandidateSelection<T>(
    private val requiredAuthorities: Set<RevocationAuthorityId>,
) {
    private val coveredAuthorities = mutableSetOf<RevocationAuthorityId>()
    private val selectedCandidates = mutableListOf<T>()

    val isComplete: Boolean
        get() = coveredAuthorities.containsAll(requiredAuthorities)

    fun consider(
        candidate: T,
        componentAuthorities: Set<RevocationAuthorityId>?,
    ) {
        if (componentAuthorities == null) {
            selectedCandidates += candidate
        } else {
            val newlyCovered = componentAuthorities
                .asSequence()
                .filter { authority -> authority in requiredAuthorities }
                .filter { authority -> authority !in coveredAuthorities }
                .toSet()
            if (newlyCovered.isNotEmpty()) {
                selectedCandidates += candidate
                coveredAuthorities += newlyCovered
            }
        }
        selectedCandidates.requireNativeOpenPgpRevocationCandidateLimit()
    }

    fun result(): List<T> = selectedCandidates.toList()
}

private sealed interface RevocationIndexInspection {
    data object Invalid : RevocationIndexInspection

    data object Unknown : RevocationIndexInspection

    data class Known(
        val certificates: List<NativeOpenPgpCertificateResolution>,
    ) : RevocationIndexInspection
}

private fun selectRevocationKeyCandidates(
    targetPrivateKeys: List<EncodedRevocationTarget>,
    targetPublicKeys: List<EncodedRevocationTarget>,
    candidates: List<GpgOpenPgpPublicKey>,
    referenceTimeEpochSeconds: Long?,
): List<GpgOpenPgpPublicKey> {
    if (candidates.isEmpty()) return emptyList()

    val targetInspections = buildList {
        targetPrivateKeys.forEach { target ->
            add(
                inspectRevocationIndex(
                    privateKeyData = target.keyData,
                    publicKeyData = null,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ).selectCertificate(target.preferredFingerprint),
            )
        }
        targetPublicKeys.forEach { target ->
            add(
                inspectRevocationIndex(
                    privateKeyData = null,
                    publicKeyData = target.keyData,
                    referenceTimeEpochSeconds = referenceTimeEpochSeconds,
                ).selectCertificate(target.preferredFingerprint),
            )
        }
    }
    if (targetInspections.any { inspection -> inspection is RevocationIndexInspection.Unknown }) {
        return candidates.distinct().requireNativeOpenPgpRevocationCandidateLimit()
    }

    val requiredAuthorities = targetInspections
        .filterIsInstance<RevocationIndexInspection.Known>()
        .asSequence()
        .flatMap { inspection -> inspection.certificates.asSequence() }
        .flatMap { certificate -> certificate.index.legacyDesignatedRevokers.asSequence() }
        .mapTo(mutableSetOf()) { revoker ->
            RevocationAuthorityId(
                publicKeyAlgorithmId = revoker.publicKeyAlgorithmId,
                fingerprint = revoker.fingerprint,
            )
        }
    if (requiredAuthorities.isEmpty()) return emptyList()

    val selection = RevocationCandidateSelection<GpgOpenPgpPublicKey>(requiredAuthorities)
    val seenArmors = mutableSetOf<String>()
    for (candidate in candidates) {
        if (selection.isComplete) break
        if (!seenArmors.add(candidate.armored)) continue
        val keyData = candidate.armored.encodeToByteArray()
        val inspection = try {
            inspectRevocationIndex(
                privateKeyData = null,
                publicKeyData = keyData,
                referenceTimeEpochSeconds = referenceTimeEpochSeconds,
            )
        } finally {
            keyData.fill(0)
        }
        when (inspection) {
            RevocationIndexInspection.Invalid -> Unit
            RevocationIndexInspection.Unknown -> selection.consider(
                candidate = candidate,
                componentAuthorities = null,
            )
            is RevocationIndexInspection.Known -> {
                val componentAuthorities = inspection.certificates
                    .asSequence()
                    .flatMap { certificate -> certificate.index.components.asSequence() }
                    .mapTo(mutableSetOf()) { component ->
                        RevocationAuthorityId(
                            publicKeyAlgorithmId = component.publicKeyAlgorithmId,
                            fingerprint = component.fingerprint,
                        )
                    }
                selection.consider(candidate, componentAuthorities)
            }
        }
    }
    return selection.result()
}

private fun RevocationIndexInspection.selectCertificate(
    preferredFingerprint: String,
): RevocationIndexInspection = when {
    preferredFingerprint.isEmpty() || this !is RevocationIndexInspection.Known -> this
    else -> copy(
        certificates = certificates.filter { certificate ->
            certificate.index.components.any { component ->
                component.fingerprint == preferredFingerprint
            }
        },
    )
}

private fun inspectRevocationIndex(
    privateKeyData: ByteArray?,
    publicKeyData: ByteArray?,
    referenceTimeEpochSeconds: Long?,
): RevocationIndexInspection {
    val metadata = NativeCrypto.openPgp.resolveMetadata(
        privateKeyData = privateKeyData,
        publicKeyData = publicKeyData,
        candidateRevocationKeys = emptyList(),
        referenceTimeEpochSeconds = referenceTimeEpochSeconds,
    ) ?: return RevocationIndexInspection.Invalid
    return if (metadata.certificates.isEmpty()) {
        RevocationIndexInspection.Unknown
    } else {
        RevocationIndexInspection.Known(metadata.certificates)
    }
}

internal fun List<ByteArray>.eraseAll() {
    forEach { value -> value.fill(0) }
}

/**
 * Keeps a key list within the current native OpenPGP request limit.
 *
 * This temporary policy preserves caller order and drops trailing documents. Consequently, an
 * oversized encryption request can omit recipients, while decryption or verification can omit the
 * matching key. A future implementation should preselect candidates from packet metadata and
 * reject oversized recipient sets instead of silently truncating them.
 */
internal fun <T> List<T>.clampToNativeOpenPgpKeyLimit(): List<T> =
    take(NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST)

internal fun <T> List<T>.requireNativeOpenPgpRevocationCandidateLimit(): List<T> {
    if (size > NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST) {
        throw NativeCryptoException(
            operation = "open_pgp_revocation_candidates",
            code = NativeCryptoErrorCode.RESOURCE_LIMIT,
        )
    }
    return this
}

internal fun throwLegacyAesUnsupported(): Nothing {
    throw IllegalArgumentException(
        "The support for AES CBC 256 (enc-type 0) is not longer provided! " +
            "Please upgrade your vault to migrate to a newer encryption type!",
    )
}
