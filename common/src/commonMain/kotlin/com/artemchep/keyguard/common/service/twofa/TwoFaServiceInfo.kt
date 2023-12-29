package com.artemchep.keyguard.common.service.twofa

data class TwoFaServiceInfo(
    val name: String,
    val domain: String,
    val domains: Set<String> = emptySet(),
    val url: String? = null,
    val documentation: String? = null,
    val notes: String? = null,
    val tfa: Set<String> = emptySet(),
)
