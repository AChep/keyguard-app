package com.artemchep.keyguard.common.model

data class AuthResult(
    val version: MasterKdfVersion,
    /**
     * The key used to encrypt all of the
     * vaults' data.
     */
    val key: MasterKey,
    val token: FingerprintPassword,
)
