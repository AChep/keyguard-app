package com.artemchep.keyguard.common.service.gpgkeyserver

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields

/**
 * Returns the primary GPG fingerprint that a keyserver refresh should target
 * for this cipher, or `null` if the cipher should not be refreshed from a
 * keyserver. Secret-key-backed items are skipped because their local secret key
 * is the source of truth for the public key material.
 */
fun DSecret.gpgKeyserverRefreshFingerprintOrNull(): String? =
    resolveGpgKeyserverRefreshKey(
        key = gpgKey,
        legacyField = { name -> fields.firstOrNull { it.name == name }?.value },
    )?.fingerprint

fun DSecret.isEligibleForGpgKeyserverRefresh(): Boolean =
    gpgKeyserverRefreshFingerprintOrNull() != null

internal data class GpgKeyserverRefreshKey(
    val publicKeyArmored: String,
    val fingerprint: String,
)

/**
 * Shared by snapshot eligibility and the final row check. Blanks are absent, but
 * nonblank material must be validated by reconciliation, never replaced by a fallback.
 * The resolved values are for selection only; they do not normalize the stored row.
 */
internal fun resolveGpgKeyserverRefreshKey(
    key: DSecret.GpgKey?,
    legacyField: (String) -> String?,
): GpgKeyserverRefreshKey? {
    fun resolveField(value: String?, name: String): String? =
        value?.takeIf(String::isNotBlank)
            ?: legacyField(name)?.takeIf(String::isNotBlank)

    val privateKeyArmored = resolveField(
        key?.privateKeyArmored,
        GpgAgentFields.PRIVATE_KEY_ARMORED,
    )
    return if (privateKeyArmored != null) {
        null
    } else {
        resolveField(key?.publicKeyArmored, GpgAgentFields.PUBLIC_KEY_ARMORED)
            ?.let { publicKeyArmored ->
                resolveGpgKeyserverFingerprint(
                    fingerprint = resolveField(key?.fingerprint, GpgAgentFields.FINGERPRINT),
                    metadata = key?.metadata,
                )?.let { fingerprint ->
                    GpgKeyserverRefreshKey(
                        publicKeyArmored = publicKeyArmored,
                        fingerprint = fingerprint,
                    )
                }
            }
    }
}
