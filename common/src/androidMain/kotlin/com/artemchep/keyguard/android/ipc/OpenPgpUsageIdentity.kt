package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpRing
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpReadFileResult
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint

internal data class OpenPgpUsageIdentity(
    val ring: GpgOpenPgpRing,
    val fingerprint: String,
    val keygrip: String?,
)

/**
 * Resolves a native-reported OpenPGP component fingerprint to exactly one
 * selected vault ring. Candidate selection alone is never proof of key use.
 */
internal fun resolveOpenPgpUsageIdentity(
    rings: List<GpgOpenPgpRing>,
    fingerprint: String?,
): OpenPgpUsageIdentity? {
    val normalizedFingerprint = fingerprint
        ?.normalizeGpgFingerprint()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    return buildList {
        rings.distinct().forEach { ring ->
            if (ring.info.fingerprint.normalizeGpgFingerprint() == normalizedFingerprint) {
                add(
                    OpenPgpUsageIdentity(
                        ring = ring,
                        fingerprint = ring.info.fingerprint,
                        keygrip = ring.info.keygrip,
                    ),
                )
            }
            ring.info.subKeys.forEach { subKey ->
                if (subKey.fingerprint.normalizeGpgFingerprint() == normalizedFingerprint) {
                    add(
                        OpenPgpUsageIdentity(
                            ring = ring,
                            fingerprint = subKey.fingerprint,
                            keygrip = subKey.keygrip,
                        ),
                    )
                }
            }
        }
    }.singleOrNull()
}

/** Returns a history target only for an attributed encrypted message. */
internal fun resolveOpenPgpDecryptionUsageIdentity(
    rings: List<GpgOpenPgpRing>,
    result: GpgOpenPgpReadFileResult,
): OpenPgpUsageIdentity? {
    val message = (result as? GpgOpenPgpReadFileResult.Message)
        ?.takeIf { it.encrypted }
        ?: return null
    return resolveOpenPgpUsageIdentity(
        rings = rings,
        fingerprint = message.decryptionKeyFingerprint,
    )
}
