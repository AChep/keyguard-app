package com.artemchep.keyguard.feature.navigation.keyboard

import androidx.compose.ui.input.key.KeyEvent

data class KeyEventInterceptorRegistration(
    val id: String,
    val block: (KeyEvent) -> Boolean,
)
