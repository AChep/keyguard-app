package com.artemchep.keyguard.common.service.licensekey

import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseSignatureVerifier
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class EcdsaP256Kg2LicenseSignatureVerifier : Kg2LicenseSignatureVerifier,
    LicenseEntitlementProofSignatureVerifier {
    override fun verify(
        publicKeyPem: String,
        signingInput: ByteArray,
        signature: ByteArray,
    ): Boolean = runCatching {
        if (signature.size != 64) {
            return@runCatching false
        }

        val publicKeyDer = publicKeyPem
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .filter { it.isNotBlank() }
            .joinToString(separator = "")
            .let(Base64.getDecoder()::decode)
        val publicKey = KeyFactory
            .getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyDer))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(signingInput)
        verifier.verify(signatureP1363ToDer(signature))
    }.getOrDefault(false)
}

private fun signatureP1363ToDer(signature: ByteArray): ByteArray {
    val r = derInteger(signature.copyOfRange(0, 32))
    val s = derInteger(signature.copyOfRange(32, 64))
    val length = r.size + s.size
    require(length < 128)
    return byteArrayOf(0x30, length.toByte()) + r + s
}

private fun derInteger(value: ByteArray): ByteArray {
    val stripped = value.dropWhile { it == 0.toByte() }
        .ifEmpty { listOf(0.toByte()) }
        .toByteArray()
    val positive = if ((stripped.first().toInt() and 0x80) != 0) {
        byteArrayOf(0) + stripped
    } else {
        stripped
    }
    require(positive.size < 128)
    return byteArrayOf(0x02, positive.size.toByte()) + positive
}
