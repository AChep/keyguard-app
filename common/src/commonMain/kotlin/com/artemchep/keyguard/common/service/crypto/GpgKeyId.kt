package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint

/** Long key ID for supported V4 (SHA-1) and V6 (SHA-256) fingerprints. */
fun String.gpgKeyIdFromFingerprintOrNull(): String? {
    val fingerprint = normalizeGpgFingerprint()
    if (fingerprint.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
    return when (fingerprint.length) {
        40 -> fingerprint.takeLast(16)
        64 -> fingerprint.take(16)
        else -> null
    }
}
