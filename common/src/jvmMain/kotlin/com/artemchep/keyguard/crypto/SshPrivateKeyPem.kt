package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.util.encoders.Base64

internal fun createPrivateKeyPem(
    type: KeyPair.Type,
    encodedPrivateKey: ByteArray,
): String {
    val format = when (type) {
        KeyPair.Type.ED25519 -> PrivateKeyPemFormat(
            header = "OPENSSH PRIVATE KEY",
            lineLength = 70,
        )

        KeyPair.Type.RSA -> if (isPkcs8PrivateKey(encodedPrivateKey)) {
            PrivateKeyPemFormat(
                header = "PRIVATE KEY",
                lineLength = 64,
            )
        } else {
            PrivateKeyPemFormat(
                header = "RSA PRIVATE KEY",
                lineLength = 64,
            )
        }
    }

    val body = Base64
        .encode(encodedPrivateKey)
        .toString(Charsets.UTF_8)
        .chunked(format.lineLength)
        .joinToString(separator = "\n")
    return buildString {
        append("-----BEGIN ")
        append(format.header)
        appendLine("-----")
        appendLine(body)
        append("-----END ")
        append(format.header)
        appendLine("-----")
    }
}

internal fun isPkcs8PrivateKey(
    encodedPrivateKey: ByteArray,
): Boolean = runCatching {
    OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(encodedPrivateKey)
}.isFailure && runCatching {
    PrivateKeyFactory.createKey(encodedPrivateKey)
}.isSuccess

private data class PrivateKeyPemFormat(
    val header: String,
    val lineLength: Int,
)
