package com.artemchep.keyguard.feature.navigation.backpress

data class BackPressInterceptorRegistration(
    val id: String,
    val block: () -> Unit,
)
