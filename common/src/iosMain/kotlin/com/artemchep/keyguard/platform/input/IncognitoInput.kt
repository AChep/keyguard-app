package com.artemchep.keyguard.platform.input

import androidx.compose.runtime.Composable

@Composable
actual fun IncognitoInput(
    content: @Composable () -> Unit,
) {
    content()
}
