package com.artemchep.keyguard.common.model

data class FingerprintPassword(
    val hash: MasterPasswordHash,
    val salt: MasterPasswordSalt,
)
