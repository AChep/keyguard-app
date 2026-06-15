package com.artemchep.keyguard.common.model

data class AddPrivilegedAppRequest(
    val packageName: String,
    val certFingerprintSha256: String,
)
