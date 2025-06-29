package com.artemchep.keyguard.common.service.passkey

import kotlin.time.Instant

data class PassKeyServiceInfo(
    val id: String,
    val name: String,
    val domain: String,
    val domains: Set<String> = emptySet(),
    val setup: String? = null,
    val documentation: String? = null,
    val notes: String? = null,
    val category: String? = null,
    val addedAt: Instant? = null,
    val features: Set<String> = emptySet(),
)
