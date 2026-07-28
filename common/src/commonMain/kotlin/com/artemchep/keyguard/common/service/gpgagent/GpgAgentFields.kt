package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.model.DSecret
import kotlinx.serialization.Serializable

object GpgAgentFields {
    const val PRIVATE_KEY_ARMORED = "keyguard.gpg.private_key.armored"
    const val PUBLIC_KEY_ARMORED = "keyguard.gpg.public_key.armored"
    const val FINGERPRINT = "keyguard.gpg.fingerprint"
}

@Serializable
data class GpgAgentKeyMetadata(
    val version: Int = 1,
    val keys: List<GpgAgentKeyMetadataKey> = emptyList(),
)

@Serializable
data class GpgAgentKeyMetadataKey(
    val keygrip: String,
    val fingerprint: String,
    val algorithm: String = "",
    val capabilities: Set<String> = emptySet(),
) {
    val canSign: Boolean
        get() = capabilities.any { it.equals("sign", ignoreCase = true) || it.equals("s", ignoreCase = true) }

    val canDecrypt: Boolean
        get() = capabilities.any {
            it.equals("encrypt", ignoreCase = true) ||
                    it.equals("e", ignoreCase = true) ||
                    it.equals("decrypt", ignoreCase = true)
        }
}

val GpgAgentKeyMetadataKey.isUsableAgentKey: Boolean
    get() = keygrip.isNotBlank() && (canSign || canDecrypt)

data class GpgAgentSecret(
    val cipher: DSecret,
    val privateKeyArmored: String?,
    val publicKeyArmored: String?,
    val fingerprint: String?,
    val metadata: GpgAgentKeyMetadata,
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
    if (metadata.keys.none { it.isUsableAgentKey }) {
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
