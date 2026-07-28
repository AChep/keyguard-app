package com.artemchep.keyguard.common.model

data class VerifyGpgPublicKeyRequest(
    val cipherId: String,
    val accountId: String,
)
