package com.artemchep.keyguard.common.service.licensekey

fun interface LicenseSignatureVerifier {
    fun verify(
        publicKeyPem: String,
        signingInput: ByteArray,
        signature: ByteArray,
    ): Boolean
}
