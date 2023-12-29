package com.artemchep.keyguard.common.model

data class CheckPasswordLeakRequest(
    val password: String,
    val cache: Boolean = true,
)
