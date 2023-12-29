package com.artemchep.keyguard.common.service.passkey

data class PassKeyServiceInfo(
    val name: String,
    val domain: String,
    val domains: Set<String> = emptySet(),
    val setup: String? = null,
    val documentation: String? = null,
    val notes: String? = null,
    val features: Set<String> = emptySet(),
)
