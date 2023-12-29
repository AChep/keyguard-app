package com.artemchep.keyguard.crypto.util

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

fun sha1(
    input: ByteArray,
): ByteArray = run {
    val digest: MessageDigest
    try {
        digest = MessageDigest.getInstance("SHA-1")
    } catch (e: NoSuchAlgorithmException) {
        throw e
    }
    digest.reset()
    digest.digest(input)
}
