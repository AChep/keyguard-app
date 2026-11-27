package com.artemchep.keyguard.common.service.crypto

/**
 * The SSH key fingerprint in the form OpenSSH prints. Older Keyguard items
 * keep the Base64 padding that OpenSSH omits.
 */
fun String.normalizeSshFingerprint(): String =
    if (startsWith("SHA256:")) removeSuffix("=") else this
