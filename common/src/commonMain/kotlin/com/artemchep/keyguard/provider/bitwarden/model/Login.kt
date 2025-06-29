package com.artemchep.keyguard.provider.bitwarden.model

import kotlin.time.Instant

data class Login(
    val accessToken: String,
    val accessTokenType: String,
    val accessTokenExpiryDate: Instant,
    val refreshToken: String,
    val scope: String?,
)
