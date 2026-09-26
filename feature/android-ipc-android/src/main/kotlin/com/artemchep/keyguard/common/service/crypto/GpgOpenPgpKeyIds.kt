package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint

internal const val OPENPGP_HEX_RADIX = 16
internal const val OPENPGP_KEY_ID_HEX_LENGTH = 16

internal fun openPgpKeyIdToLong(keyId: String): Long {
    val normalized = keyId.normalizeGpgFingerprint()
    require(
        normalized.length in 1..OPENPGP_KEY_ID_HEX_LENGTH &&
                normalized.all { it in '0'..'9' || it in 'A'..'F' },
    ) {
        "Invalid OpenPGP key ID."
    }
    return normalized
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
