package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.decodeOrNull
import com.artemchep.keyguard.common.util.toHex

private val publicKeyPartsRegex = Regex("\\s+")
private val publicKeyBlobBase64Regex = Regex("^[A-Za-z0-9+/]+={0,2}$")
private val base64WhitespaceRegex = Regex("\\s+")

fun parseSshAgentPublicKeyMaterial(
    publicKey: String,
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
): SshAgentPublicKeyMaterial? {
    val parts = publicKey
        .trim()
        .split(publicKeyPartsRegex)
    val keyType = parts.getOrNull(0)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val encodedKey = parts.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (!publicKeyBlobBase64Regex.matches(encodedKey) || encodedKey.length % 4 == 1) {
        return null
    }

    val publicKeyBlob = base64Service.decodeOrNull(encodedKey)
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val normalizedPublicKey = keyType + " " + base64Service
        .encodeToString(publicKeyBlob)
        .replace(base64WhitespaceRegex, "")
    val publicKeyBlobSha256 = cryptoGenerator
        .hashSha256(publicKeyBlob)
        .toHex()
    return SshAgentPublicKeyMaterial(
        publicKeyBlobSha256 = publicKeyBlobSha256,
        publicKey = normalizedPublicKey,
        keyType = keyType,
    )
}

fun createSshAgentPublicKeyRow(
    publicKey: String,
    fingerprint: String,
    name: String?,
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
): SshAgentPublicKeyRow? {
    val material = parseSshAgentPublicKeyMaterial(
        publicKey = publicKey,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
    ) ?: return null
    return SshAgentPublicKeyRow(
        publicKeyBlobSha256 = material.publicKeyBlobSha256,
        publicKey = material.publicKey,
        keyType = material.keyType,
        fingerprint = fingerprint,
        name = name?.takeIf { it.isNotBlank() },
    )
}
