package com.artemchep.keyguard.crypto.util

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

fun sha256(
    input: ByteArray,
): ByteArray = run {
    val digest: MessageDigest
    try {
        digest = MessageDigest.getInstance("SHA-256")
    } catch (e: NoSuchAlgorithmException) {
        throw e
    }
    digest.reset()
    digest.digest(input)
}
