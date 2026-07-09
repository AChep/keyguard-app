package com.artemchep.keyguard.sshe2e

data class TestSshKey(
    val name: String,
    val principal: String,
    val privateKeyPem: String,
    val publicKey: String,
    val keyType: String,
    val fingerprint: String,
)
