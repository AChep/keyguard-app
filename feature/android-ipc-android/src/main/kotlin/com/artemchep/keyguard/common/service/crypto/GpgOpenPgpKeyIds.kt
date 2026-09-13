package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint

internal const val OPENPGP_HEX_RADIX = 16
internal const val OPENPGP_KEY_ID_HEX_LENGTH = 16

internal fun fingerprintToKeyId(fingerprint: String): Long {
    val normalized = fingerprint.normalizeGpgFingerprint()
    require(
        normalized.length >= OPENPGP_KEY_ID_HEX_LENGTH &&
                normalized.all { it in '0'..'9' || it in 'A'..'F' },
    ) {
        "Invalid OpenPGP fingerprint."
    }
    return normalized
        .takeLast(OPENPGP_KEY_ID_HEX_LENGTH)
        .toULong(OPENPGP_HEX_RADIX)
        .toLong()
}

internal fun <T> resolveUniqueOpenPgpKeyIds(
    keyIds: List<Long>,
    candidates: List<T>,
    candidateKeyIds: (T) -> Set<Long>,
): List<T>? {
    val resolved = keyIds
        .distinct()
        .map { keyId ->
            candidates
                .singleOrNull { keyId in candidateKeyIds(it) }
        }
    return resolved
        .takeIf { it.all { candidate -> candidate != null } }
        ?.filterNotNull()
        ?.distinct()
}

internal fun <T> hasOpenPgpKeyIdCollision(
    selected: List<T>,
    candidates: List<T>,
    candidateKeyIds: (T) -> Set<Long>,
): Boolean {
    val occurrences = HashMap<Long, Int>()
    candidates.forEach { candidate ->
        candidateKeyIds(candidate).forEach { keyId ->
            occurrences[keyId] = (occurrences[keyId] ?: 0) + 1
        }
    }
    return selected.any { target ->
        candidateKeyIds(target).any { keyId ->
            occurrences[keyId] != 1
        }
    }
}
