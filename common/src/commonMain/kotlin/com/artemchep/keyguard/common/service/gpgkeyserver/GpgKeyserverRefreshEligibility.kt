package com.artemchep.keyguard.common.service.gpgkeyserver

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentFingerprint
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentPrivateKeyArmored
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentPublicKeyArmored
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.parseGpgAgentMetadataOrNull

/**
 * Returns the primary GPG fingerprint that a keyserver refresh should target
 * for this cipher, or `null` if the cipher should not be refreshed from a
 * keyserver. Secret-key-backed items are skipped because their local secret key
 * is the source of truth for the public key material.
 */
fun DSecret.gpgKeyserverRefreshFingerprintOrNull(): String? {
    if (!isEligibleForGpgKeyserverRefresh()) {
        return null
    }
    return getGpgAgentFingerprint()
        ?.normalizeGpgFingerprint()
        ?.takeIf { it.isNotEmpty() }
        ?: parseGpgAgentMetadataOrNull()
            ?.certificates
            ?.firstOrNull { it.primaryFingerprint.isNotBlank() }
            ?.primaryFingerprint
            ?.normalizeGpgFingerprint()
            ?.takeIf { it.isNotEmpty() }
}

fun DSecret.isEligibleForGpgKeyserverRefresh(): Boolean =
    getGpgAgentPublicKeyArmored()?.isNotBlank() == true &&
            getGpgAgentPrivateKeyArmored()?.isBlank() != false &&
            hasGpgKeyserverRefreshFingerprint()

private fun DSecret.hasGpgKeyserverRefreshFingerprint(): Boolean =
    getGpgAgentFingerprint()
        ?.normalizeGpgFingerprint()
        ?.takeIf { it.isNotEmpty() } != null ||
            parseGpgAgentMetadataOrNull()
                ?.certificates
                ?.any { certificate ->
                    certificate.primaryFingerprint
                        .normalizeGpgFingerprint()
                        .isNotEmpty()
                } == true
