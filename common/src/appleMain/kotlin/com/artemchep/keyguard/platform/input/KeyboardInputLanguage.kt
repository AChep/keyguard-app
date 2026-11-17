package com.artemchep.keyguard.platform.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
actual fun rememberKeyboardInputLanguage(
    enabled: Boolean,
): State<String?> = remember {
    mutableStateOf(null)
}
