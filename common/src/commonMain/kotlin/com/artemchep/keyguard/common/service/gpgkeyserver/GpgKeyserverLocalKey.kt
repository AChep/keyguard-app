package com.artemchep.keyguard.common.service.gpgkeyserver

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.isCanonical
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint

/** Public-key fields from a current vault row, validated before retaining evidence. */
data class GpgKeyserverLocalKey(
    val cipherId: String,
    val fingerprint: String?,
    val publicKeyArmored: String,
)

internal fun DSecret.toGpgKeyserverLocalKey(): GpgKeyserverLocalKey? = gpgKeyserverLocalKey(
    cipherId = id,
    publicKeyArmored = gpgKey?.publicKeyArmored,
    fingerprint = gpgKey?.fingerprint,
    metadata = gpgKey?.metadata,
    legacyField = { name -> fields.firstOrNull { it.name == name }?.value },
)

/** Use the same blank-aware typed/legacy fields for transactional and offline evaluations. */
internal fun gpgKeyserverLocalKey(
    cipherId: String,
    publicKeyArmored: String?,
    fingerprint: String?,
    metadata: GpgAgentKeyMetadata?,
    legacyField: (String) -> String?,
): GpgKeyserverLocalKey? {
    val publicKey = publicKeyArmored?.takeIf { it.isNotBlank() }
        ?: legacyField(GpgAgentFields.PUBLIC_KEY_ARMORED)?.takeIf { it.isNotBlank() }
        ?: return null
    val storedFingerprint = fingerprint?.takeIf { it.isNotBlank() }
        ?: legacyField(GpgAgentFields.FINGERPRINT)?.takeIf { it.isNotBlank() }
    return GpgKeyserverLocalKey(
        cipherId = cipherId,
        fingerprint = resolveGpgKeyserverFingerprint(storedFingerprint, metadata),
        publicKeyArmored = publicKey,
    )
}

internal fun resolveGpgKeyserverFingerprint(
    fingerprint: String?,
    metadata: GpgAgentKeyMetadata?,
): String? = fingerprint?.normalizeGpgFingerprint()?.takeIf { it.isNotEmpty() }
    ?: metadata?.takeIf { it.isCanonical }
        ?.certificates?.firstOrNull { it.primaryFingerprint.isNotBlank() }
        ?.primaryFingerprint?.normalizeGpgFingerprint()?.takeIf { it.isNotEmpty() }
