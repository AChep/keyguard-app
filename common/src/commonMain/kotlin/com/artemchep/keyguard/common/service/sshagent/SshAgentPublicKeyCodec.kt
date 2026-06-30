package com.artemchep.keyguard.common.service.sshagent

import kotlin.io.encoding.Base64

fun extractSshKeyType(publicKey: String): String? =
    publicKey.trim().splitToSequence(Regex("\\s+")).firstOrNull()

fun sshPublicKeysMatch(
    left: String,
    right: String,
): Boolean {
    val leftBlob = decodeSshPublicKeyBlob(left) ?: return false
    val rightBlob = decodeSshPublicKeyBlob(right) ?: return false
    return leftBlob.contentEquals(rightBlob)
}

/**
 * Decodes the base64 key body of an OpenSSH public key line
 * ("ssh-ed25519 AAAA... comment" → the decoded "AAAA..." blob), so keys can be
 * compared regardless of the type prefix / comment suffix.
 */
fun decodeSshPublicKeyBlob(
    publicKey: String,
): ByteArray? = runCatching {
    val encodedKeyBase64 = publicKey
        .trim()
        .splitToSequence(Regex("\\s+"))
        .drop(1)
        .firstOrNull()
        ?.trim()
    if (encodedKeyBase64.isNullOrEmpty()) {
        null
    } else {
        Base64.decode(encodedKeyBase64)
    }
}.getOrNull()
