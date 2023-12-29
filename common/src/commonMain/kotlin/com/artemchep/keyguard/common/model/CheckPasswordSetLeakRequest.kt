package com.artemchep.keyguard.common.model

data class CheckPasswordSetLeakRequest(
    val passwords: Set<String>,
    val cache: Boolean = true,
)
