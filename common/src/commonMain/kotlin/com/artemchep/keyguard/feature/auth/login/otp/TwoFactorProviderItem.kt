package com.artemchep.keyguard.feature.auth.login.otp

data class TwoFactorProviderItem(
    val key: String,
    val title: String,
    val checked: Boolean,
    val onClick: (() -> Unit)? = null,
)
