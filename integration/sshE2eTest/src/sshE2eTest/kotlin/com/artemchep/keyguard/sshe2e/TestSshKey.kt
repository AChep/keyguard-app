package com.artemchep.keyguard.sshe2e

data class TestSshKey(
    val name: String,
    val principal: String,
    val privateKeyPem: String,
    val publicKey: String,
    val keyType: String,
    val fingerprint: String,
    /** An OpenSSH certificate line that certifies the [publicKey]. */
    val certificate: String? = null,
)
