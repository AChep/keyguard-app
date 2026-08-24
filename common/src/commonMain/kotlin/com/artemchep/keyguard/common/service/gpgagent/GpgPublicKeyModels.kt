package com.artemchep.keyguard.common.service.gpgagent

/**
 * Write model of the exposed GPG public key catalog: one entry per
 * cipher, carrying the per-component-key [KeyInfo] rows the desktop
 * gpg-agent serves while the vault is locked.
 */
data class GpgPublicKeyEntry(
    val accountId: String,
    val cipherId: String,
    val publicKeyArmored: String?,
    val primaryFingerprint: String?,
    val canSign: Boolean,
    val canDecrypt: Boolean,
    val name: String?,
    val keyInfo: List<KeyInfo>,
) {
    data class KeyInfo(
        val keygrip: String,
        val fingerprint: String,
        val algorithm: String,
        val canSign: Boolean,
        val canDecrypt: Boolean,
    )
}

/**
 * A complete per-cipher catalog row: unlike [GpgPublicKeyEntry] the
 * armored key and the primary fingerprint are guaranteed to be present.
 */
data class GpgPublicKeyRow(
    val accountId: String,
    val cipherId: String,
    val publicKeyArmored: String,
    val primaryFingerprint: String,
    val canSign: Boolean,
    val canDecrypt: Boolean,
    val name: String?,
)

/**
 * Builds the canonical catalog entry for a secret. Both the persisted
 * catalog and the live, unlocked-vault KEYINFO listing map through this
 * function, so the agent's answers cannot drift between the locked and
 * unlocked states.
 */
fun GpgAgentSecret.toGpgPublicKeyEntry(
    name: String?,
): GpgPublicKeyEntry {
    val hasPrivateKey = hasPrivateKey
    val agentKeys = metadata.routableAgentKeys
    val authorizedSigningKeygrips = authorizedAgentKeys
        .asSequence()
        .filter(GpgAgentKeyMetadataKey::canSign)
        .map { key -> key.keygrip.normalizeGpgKeygrip() }
        .toSet()
    return GpgPublicKeyEntry(
        accountId = cipher.accountId,
        cipherId = cipher.id,
        publicKeyArmored = publicKeyArmored
            ?.takeIf(String::isNotBlank),
        primaryFingerprint = fingerprint
            ?.takeIf(String::isNotBlank)
            ?: agentKeys.firstNotNullOfOrNull {
                it.fingerprint.takeIf(String::isNotBlank)
            },
        canSign = hasPrivateKey && authorizedSigningKeygrips.isNotEmpty(),
        canDecrypt = hasPrivateKey && agentKeys.any { it.canDecrypt },
        name = name,
        keyInfo = agentKeys
            .filter { it.isUsableAgentKey }
            .map { key ->
                GpgPublicKeyEntry.KeyInfo(
                    keygrip = key.keygrip.normalizeGpgKeygrip(),
                    fingerprint = key.fingerprint.ifBlank {
                        fingerprint.orEmpty()
                    },
                    algorithm = key.algorithm,
                    canSign = hasPrivateKey &&
                        key.keygrip.normalizeGpgKeygrip() in authorizedSigningKeygrips,
                    canDecrypt = hasPrivateKey && key.canDecrypt,
                )
            },
    )
}

data class GpgAgentKeyInfoRow(
    val accountId: String,
    val cipherId: String,
    val keygrip: String,
    val fingerprint: String,
    val algorithm: String,
    val canSign: Boolean,
    val canDecrypt: Boolean,
    val name: String?,
) {
    val displayName: String
        get() = name
            ?.takeIf { it.isNotBlank() }
            ?: fingerprint.takeIf { it.isNotBlank() }
            ?: keygrip
}
