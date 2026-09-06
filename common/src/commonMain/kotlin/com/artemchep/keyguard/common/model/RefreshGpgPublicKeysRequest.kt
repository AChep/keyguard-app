package com.artemchep.keyguard.common.model

data class RefreshGpgPublicKeysRequest(
    val cipherIds: Set<String>,
    val accountId: String? = null,
)

data class RefreshGpgPublicKeysResult(
    val refreshed: Int,
    val notFound: Int,
    val skipped: Int,
    val failed: Int = 0,
)
