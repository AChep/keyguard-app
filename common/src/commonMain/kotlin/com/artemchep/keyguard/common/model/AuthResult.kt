package com.artemchep.keyguard.common.model

data class AuthResult(
    /**
     * The key used to encrypt all of the
     * vaults' data.
     */
    val key: MasterKey,
    val token: FingerprintPassword,
)
