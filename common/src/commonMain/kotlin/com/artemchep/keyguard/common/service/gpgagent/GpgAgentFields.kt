package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.serialization.Serializable

object GpgAgentFields {
    const val PRIVATE_KEY_ARMORED = "keyguard.gpg.private_key.armored"
    const val PUBLIC_KEY_ARMORED = "keyguard.gpg.public_key.armored"
    const val FINGERPRINT = "keyguard.gpg.fingerprint"
}

@Serializable
data class GpgAgentKeyMetadata(
    val certificates: List<GpgAgentCertificateMetadata> = emptyList(),
)

data class GpgAgentMetadataResolution(
    val metadata: GpgAgentKeyMetadata,
    val authorization: GpgAgentAuthorizationSnapshot,
)

val GpgAgentMetadataResolution.authorizedAgentKeys: List<GpgAgentKeyMetadataKey>
    get() = authorization.authorizedAgentKeys

val GpgAgentAuthorizationSnapshot?.authorizedAgentKeys: List<GpgAgentKeyMetadataKey>
    get() = this
        ?.takeIf(GpgAgentAuthorizationSnapshot::isSupported)
        ?.let { snapshot ->
            snapshot.keys.filter { key ->
                snapshot.revocations[key.fingerprint.normalizeGpgFingerprint()] == GpgRevocationStatus.NOT_REVOKED &&
                    key.isUsableAgentKey
            }
        }
        .orEmpty()

data class GpgAgentAuthorizationSnapshot(
    val evaluatedAtEpochSeconds: Long,
    val policyRevision: Int,
    val keys: List<GpgAgentKeyMetadataKey>,
    /**
     * Per-component renewal authorization from the same evaluation, keyed by
     * normalized component fingerprint.
     *
     * This is policy output and shares the snapshot's transient lifetime: it is
     * valid only for [policyRevision] at [evaluatedAtEpochSeconds] and must
     * never be written into the persisted certificate index.
     */
    val renewals: Map<String, GpgRenewalAuthorization> = emptyMap(),
    /** Effective revocation state at this evaluation time; missing entries are indeterminate. */
    val revocations: Map<String, GpgRevocationStatus> = emptyMap(),
) {
    val isSupported: Boolean
        get() = policyRevision == SUPPORTED_POLICY_REVISION

    companion object {
        const val SUPPORTED_POLICY_REVISION = 2
    }
}

/**
 * Whether recertification may reissue a component's own self-signatures.
 *
 * [TEMPLATE_ONLY] is the legacy rescue tier: the component authenticates
 * nothing, because its self-signatures use a hash below policy, yet renewal
 * stays available — the renewal is what replaces them with modern ones. It
 * authorizes nothing else. This is deliberately not `@Serializable`: it is
 * transient policy output, never part of the persisted index.
 */
enum class GpgRenewalAuthorization {
    AUTHENTICATED,
    TEMPLATE_ONLY,
    NONE,
}

/** Transient policy output, never part of the persisted certificate index. */
enum class GpgRevocationStatus {
    NOT_REVOKED,
    REVOKED,
    INDETERMINATE,
}

@Serializable
data class GpgAgentCertificateMetadata(
    val primaryFingerprint: String,
    val components: List<GpgAgentKeyComponentMetadata> = emptyList(),
    val legacyDesignatedRevokers: List<GpgAgentLegacyDesignatedRevoker> = emptyList(),
)

@Serializable
data class GpgAgentKeyComponentMetadata(
    val fingerprint: String,
    val role: GpgAgentKeyComponentRole,
    val publicKeyAlgorithmId: Int,
    val algorithm: String = "",
    val keygrips: List<String> = emptyList(),
    val storedSecretMaterial: Boolean = false,
    val agentOperations: Set<GpgAgentOperation> = emptySet(),
)

@Serializable
enum class GpgAgentKeyComponentRole {
    PRIMARY,
    SUBKEY,
}

@Serializable
data class GpgAgentLegacyDesignatedRevoker(
    val publicKeyAlgorithmId: Int,
    val fingerprint: String,
    val keyClass: Int,
    val sensitive: Boolean,
)

@Serializable
data class GpgAgentKeyMetadataKey(
    val keygrip: String,
    val fingerprint: String,
    val algorithm: String = "",
    val capabilities: Set<String> = emptySet(),
) {
    val canSign: Boolean
        get() = capabilities.any {
            it.equals("sign", ignoreCase = true) ||
                    it.equals("s", ignoreCase = true)
        }

    val canDecrypt: Boolean
        get() = capabilities.any {
            it.equals("encrypt", ignoreCase = true) ||
                    it.equals("e", ignoreCase = true) ||
                    it.equals("decrypt", ignoreCase = true)
        }
}

/**
 * Stable component routing information. This deliberately does not authorize signing or
 * encryption: native policy remains authoritative for those operations. Historical decryption
 * must continue to find exact secret components after expiration or revocation.
 */
val GpgAgentKeyMetadata.routableAgentKeys: List<GpgAgentKeyMetadataKey>
    get() = certificates.flatMap { certificate ->
        // Secret-material filtering is per certificate: a certificate that stores secret
        // material exposes only its secret components, while a purely public certificate
        // exposes all of them.
        val certificateHasSecretMaterial = certificate.components.any { component ->
            component.storedSecretMaterial
        }
        certificate.components.mapNotNull { component ->
            if (certificateHasSecretMaterial && !component.storedSecretMaterial) {
                return@mapNotNull null
            }
            val keygrip = component.keygrips.firstOrNull()
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val capabilities = component.agentOperations
                .mapTo(mutableSetOf()) { operation ->
                    when (operation) {
                        GpgAgentOperation.SIGN -> "sign"
                        GpgAgentOperation.DECRYPT -> "decrypt"
                    }
                }
            GpgAgentKeyMetadataKey(
                keygrip = keygrip,
                fingerprint = component.fingerprint,
                algorithm = component.algorithm,
                capabilities = capabilities,
            )
        }
    }

val GpgAgentKeyMetadata.isCanonical: Boolean
    get() = certificates.isNotEmpty() &&
            certificates
                .all { certificate ->
                    certificate.primaryFingerprint.isNotBlank() &&
                            certificate.components.isNotEmpty()
                }

val GpgAgentKeyMetadataKey.isUsableAgentKey: Boolean
    get() = keygrip.isNotBlank() && (canSign || canDecrypt)

data class GpgAgentSecret(
    val cipher: DSecret,
    val privateKeyArmored: String?,
    val publicKeyArmored: String?,
    val fingerprint: String?,
    val metadata: GpgAgentKeyMetadata,
    val authorization: GpgAgentAuthorizationSnapshot? = null,
)

val GpgAgentSecret.authorizedAgentKeys: List<GpgAgentKeyMetadataKey>
    get() = authorization.authorizedAgentKeys

private fun GpgAgentSecret.resolveAuthorization(
    resolver: GpgKeyMetadataResolver,
    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
): GpgAgentSecret {
    val resolved = resolver.resolve(
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        candidateRevocationKeys = candidateRevocationKeys,
    )
    return copy(
        metadata = resolved?.metadata ?: metadata,
        authorization = resolved?.authorization,
    )
}

/**
 * Like [resolveAuthorization], but a non-fatal resolver failure strips the
 * transient authorization instead of propagating.
 */
fun GpgAgentSecret.resolveAuthorizationOrClear(
    resolver: GpgKeyMetadataResolver,
    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    onError: (Exception) -> Unit = {},
): GpgAgentSecret = try {
    resolveAuthorization(
        resolver = resolver,
        candidateRevocationKeys = candidateRevocationKeys,
    )
} catch (e: Exception) {
    e.throwIfFatalOrCancellation()
    onError(e)
    copy(authorization = null)
}

/**
 * Like [resolveAuthorizationOrClear], but with the shared error-reporting
 * policy: a non-fatal resolver failure is posted to the [logRepository].
 */
fun GpgAgentSecret.resolveAuthorizationOrClear(
    resolver: GpgKeyMetadataResolver,
    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    logRepository: LogRepository,
    tag: String,
): GpgAgentSecret = resolveAuthorizationOrClear(
    resolver = resolver,
    candidateRevocationKeys = candidateRevocationKeys,
    onError = { e ->
        logRepository.post(
            tag = tag,
            message = "Failed to resolve live GPG authorization: ${e.message}",
            level = LogLevel.ERROR,
        )
    },
)

val GpgAgentSecret.hasPrivateKey: Boolean
    get() = privateKeyArmored?.isNotBlank() == true

internal fun DSecret.isEligibleForGpgAgent(): Boolean = toGpgAgentSecretOrNull() != null

fun DSecret.toGpgAgentSecretOrNull(): GpgAgentSecret? {
    if (!isGpgAgentSecretType() || deleted) {
        return null
    }
    val privateKeyArmored = getGpgAgentPrivateKeyArmored()
        ?.takeIf { it.isNotBlank() }
    val publicKeyArmored = getGpgAgentPublicKeyArmored()
        ?.takeIf { it.isNotBlank() }
    if (privateKeyArmored == null && publicKeyArmored == null) {
        return null
    }
    val metadata = parseGpgAgentMetadataOrNull()
        ?: return null
    if (metadata.routableAgentKeys.none { it.isUsableAgentKey }) {
        return null
    }
    return GpgAgentSecret(
        cipher = this,
        privateKeyArmored = privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = getGpgAgentFingerprint(),
        metadata = metadata,
    )
}

private fun DSecret.isGpgAgentSecretType(): Boolean =
    type == DSecret.Type.GpgKey ||
            type == DSecret.Type.SecureNote

fun DSecret.parseGpgAgentMetadataOrNull(): GpgAgentKeyMetadata? =
    gpgKey?.metadata

fun DSecret.getGpgAgentPrivateKeyArmored(): String? =
    gpgKey?.privateKeyArmored
        ?: getFieldValue(GpgAgentFields.PRIVATE_KEY_ARMORED)

fun DSecret.getGpgAgentPublicKeyArmored(): String? =
    gpgKey?.publicKeyArmored
        ?: getFieldValue(GpgAgentFields.PUBLIC_KEY_ARMORED)

fun DSecret.getGpgAgentFingerprint(): String? =
    gpgKey?.fingerprint
        ?: getFieldValue(GpgAgentFields.FINGERPRINT)

private fun DSecret.getFieldValue(
    name: String,
): String? = fields
    .firstOrNull { it.name == name }
    ?.value
