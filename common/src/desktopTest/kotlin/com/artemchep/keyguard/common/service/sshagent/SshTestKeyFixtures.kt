package com.artemchep.keyguard.common.service.sshagent

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.Base64

internal data class TestRsaSshKeyPair(
    val privateKeyPem: String,
    val publicKeyOpenSsh: String,
)

internal fun generateRsaSshKeyPair(): TestRsaSshKeyPair {
    val keyPair = generateJcaRsaKeyPair()
    return TestRsaSshKeyPair(
        privateKeyPem = toPkcs8PrivateKeyPem(keyPair.private),
        publicKeyOpenSsh = toOpenSshPublicKey(keyPair.public as RSAPublicKey),
    )
}

internal fun generateJcaRsaKeyPair(): KeyPair {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048, SecureRandom())
    return generator.generateKeyPair()
}

internal fun toOpenSshPublicKey(key: RSAPublicKey): String {
    val blob = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeSshString("ssh-rsa".encodeToByteArray())
            output.writeSshMpint(key.publicExponent)
            output.writeSshMpint(key.modulus)
        }
        bytes.toByteArray()
    }
    return "ssh-rsa ${Base64.getEncoder().encodeToString(blob)}"
}

internal fun toPkcs8PrivateKeyPem(privateKey: PrivateKey): String {
    val body = Base64.getEncoder().encodeToString(privateKey.encoded)
    return buildString {
        appendLine("-----BEGIN PRIVATE KEY-----")
        body.chunked(64).forEach(::appendLine)
        appendLine("-----END PRIVATE KEY-----")
    }
}

private fun DataOutputStream.writeSshMpint(value: BigInteger) =
    writeSshString(value.toByteArray())

private fun DataOutputStream.writeSshString(value: ByteArray) {
    writeInt(value.size)
    write(value)
}
