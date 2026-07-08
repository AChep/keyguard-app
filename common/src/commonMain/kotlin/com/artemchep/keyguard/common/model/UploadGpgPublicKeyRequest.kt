package com.artemchep.keyguard.common.model

data class UploadGpgPublicKeyRequest(
    val cipherId: String,
    val accountId: String,
)
