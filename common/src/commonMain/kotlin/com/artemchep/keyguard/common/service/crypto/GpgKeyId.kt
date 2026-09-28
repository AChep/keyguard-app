package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint

private const val GPG_V4_FINGERPRINT_LENGTH = 40
private const val GPG_V6_FINGERPRINT_LENGTH = 64
private const val GPG_LONG_KEY_ID_LENGTH = 16

/** Long key ID for supported V4 (SHA-1) and V6 (SHA-256) fingerprints. */
fun String.gpgKeyIdFromFingerprintOrNull(): String? {
    val fingerprint = normalizeGpgFingerprint()
    if (fingerprint.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
    return when (fingerprint.length) {
        GPG_V4_FINGERPRINT_LENGTH -> fingerprint.takeLast(GPG_LONG_KEY_ID_LENGTH)
        GPG_V6_FINGERPRINT_LENGTH -> fingerprint.take(GPG_LONG_KEY_ID_LENGTH)
        else -> null
    }
}
