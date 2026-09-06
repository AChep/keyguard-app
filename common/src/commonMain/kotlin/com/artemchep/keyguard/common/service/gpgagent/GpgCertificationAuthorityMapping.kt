package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository

/**
 * The shared resolver failure policy: a non-fatal failure is reported to
 * [onError] and treated as an absent result, so callers always fail closed.
 */
internal inline fun <T : Any> resolveNonFatalOrNull(
    onError: (Exception) -> Unit,
    block: () -> T?,
): T? = runCatchingNonFatal(block).getOrElse { e ->
    if (e !is Exception) throw e
    onError(e)
    null
}

/**
 * The shared error-reporting policy for resolver failures: post the
 * failure to the [logRepository] and treat the result as absent.
 */
internal fun gpgResolverErrorLogger(
    logRepository: LogRepository,
    tag: String,
    what: String,
): (Exception) -> Unit = { e ->
    logRepository.post(
        tag = tag,
        message = "Failed to resolve $what: ${e.message}",
        level = LogLevel.ERROR,
    )
}

/**
 * Returns locally owned primary certificates independently of whether any
 * component is routable through the GPG agent. A certification-only primary
 * remains a valid trust authority even when it cannot sign payload data or
 * decrypt messages.
 *
 * Persisted metadata is only an index and cannot prove current ownership. The
 * live resolver must parse the stored private material and attribute it to the
 * primary component before that certificate becomes a trust authority.
 */
fun DSecret.toGpgCertificationAuthorityEntries(
    resolver: GpgKeyMetadataResolver,
    candidateRevocationKeys: List<GpgOpenPgpPublicKey> = emptyList(),
    onError: (Exception) -> Unit = {},
): List<GpgCertificationAuthorityEntry> = when {
    !isGpgAgentSecretType() || deleted -> emptyList()
    else -> {
        val privateKeyArmored = getGpgAgentPrivateKeyArmored()
            ?.takeIf(String::isNotBlank)
        val publicKeyArmored = getGpgAgentPublicKeyArmored()
            ?.takeIf(String::isNotBlank)
        if (privateKeyArmored == null || publicKeyArmored == null) {
            emptyList()
        } else {
            resolveNonFatalOrNull(onError) {
                resolver.resolve(
                    privateKeyArmored = privateKeyArmored,
                    publicKeyArmored = publicKeyArmored,
                    fingerprint = getGpgAgentFingerprint(),
                    candidateRevocationKeys = candidateRevocationKeys,
                )?.metadata
            }?.toGpgCertificationAuthorityEntries(
                accountId = accountId,
                cipherId = id,
                publicKeyArmored = publicKeyArmored,
            ).orEmpty()
        }
    }
}

/**
 * Like [toGpgCertificationAuthorityEntries], but with the shared
 * error-reporting policy: a non-fatal resolver failure is posted to the
 * [logRepository].
 */
fun DSecret.toGpgCertificationAuthorityEntries(
    resolver: GpgKeyMetadataResolver,
    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    logRepository: LogRepository,
    tag: String,
): List<GpgCertificationAuthorityEntry> = toGpgCertificationAuthorityEntries(
    resolver = resolver,
    candidateRevocationKeys = candidateRevocationKeys,
    onError = gpgResolverErrorLogger(
        logRepository = logRepository,
        tag = tag,
        what = "live GPG certification authority metadata",
    ),
)

/**
 * Like the [DSecret] variant, but reuses the resolution already performed by
 * [resolveAuthorizationOrClear] instead of parsing the key material again.
 * A secret without a live [GpgAgentSecret.authorization] snapshot carries
 * only the persisted metadata index, which cannot prove current ownership,
 * so it yields no authorities.
 */
fun GpgAgentSecret.toGpgCertificationAuthorityEntries(): List<GpgCertificationAuthorityEntry> {
    val publicKey = publicKeyArmored
    return if (authorization == null || privateKeyArmored == null || publicKey == null) {
        emptyList()
    } else {
        metadata.toGpgCertificationAuthorityEntries(
            accountId = cipher.accountId,
            cipherId = cipher.id,
            publicKeyArmored = publicKey,
        )
    }
}

private fun GpgAgentKeyMetadata.toGpgCertificationAuthorityEntries(
    accountId: String,
    cipherId: String,
    publicKeyArmored: String,
): List<GpgCertificationAuthorityEntry> = certificates.mapNotNull { certificate ->
    val ownsPrimaryKey = certificate.components.any { component ->
        component.role == GpgAgentKeyComponentRole.PRIMARY && component.storedSecretMaterial
    }
    if (!ownsPrimaryKey) return@mapNotNull null
    val primaryFingerprint = certificate.primaryFingerprint
        .normalizeGpgFingerprint()
        .takeIf(String::isNotBlank)
        ?: return@mapNotNull null
    GpgCertificationAuthorityEntry(
        accountId = accountId,
        cipherId = cipherId,
        publicKeyArmored = publicKeyArmored,
        primaryFingerprint = primaryFingerprint,
    )
}
