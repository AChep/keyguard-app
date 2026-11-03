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

/** A locally owned primary key trusted to certify exact OpenPGP User IDs. */
data class GpgCertificationAuthorityEntry(
    val accountId: String,
    val cipherId: String,
    val publicKeyArmored: String,
    val primaryFingerprint: String,
)

/**
 * A consistent read of the public key catalog and the authorities that
 * certify identities in that catalog.
 */
data class GpgPublicKeySnapshot(
    val publicKeys: List<GpgPublicKeyRow>,
    val certificationAuthorities: List<GpgCertificationAuthorityEntry>,
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
    val agentKeys = metadata.routableAgentKeys
    val signingKeygrips = agentKeygripsFor(GpgAgentOperation.SIGN)
    val decryptionKeygrips = agentKeygripsFor(GpgAgentOperation.DECRYPT)
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
        canSign = signingKeygrips.isNotEmpty(),
        canDecrypt = decryptionKeygrips.isNotEmpty(),
        name = name,
        keyInfo = agentKeys
            .filter { it.isUsableAgentKey }
            .map { key ->
                val keygrip = key.keygrip.normalizeGpgKeygrip()
                GpgPublicKeyEntry.KeyInfo(
                    keygrip = keygrip,
                    fingerprint = key.fingerprint.ifBlank {
                        fingerprint.orEmpty()
                    },
                    algorithm = key.algorithm,
                    canSign = keygrip in signingKeygrips,
                    canDecrypt = keygrip in decryptionKeygrips,
                )
            },
    )
}

private fun GpgAgentSecret.agentKeygripsFor(
    operation: GpgAgentOperation,
): Set<String> = agentKeysFor(operation)
    .mapTo(mutableSetOf()) { key -> key.keygrip.normalizeGpgKeygrip() }

internal fun GpgPublicKeyEntry.toGpgAgentKeyInfoRows(): List<GpgAgentKeyInfoRow> =
    keyInfo.map { key ->
        GpgAgentKeyInfoRow(
            accountId = accountId,
            cipherId = cipherId,
            keygrip = key.keygrip,
            fingerprint = key.fingerprint,
            algorithm = key.algorithm,
            canSign = key.canSign,
            canDecrypt = key.canDecrypt,
            name = name,
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

    /** Reads the stored result of [agentKeysFor] for this component. */
    fun supports(operation: GpgAgentOperation): Boolean = when (operation) {
        GpgAgentOperation.SIGN -> canSign
        GpgAgentOperation.DECRYPT -> canDecrypt
    }
}
