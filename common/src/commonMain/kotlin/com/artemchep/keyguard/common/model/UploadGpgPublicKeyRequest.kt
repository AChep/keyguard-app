package com.artemchep.keyguard.common.model

data class UploadGpgPublicKeyRequest(
    val cipherId: String,
    val accountId: String,
    /** Addresses to request verification e-mails for. VKS only, ignored on HKP. */
    val verifyEmails: Set<String> = emptySet(),
)
