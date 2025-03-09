package com.artemchep.keyguard.platform.input

import androidx.compose.runtime.Composable

@Composable
expect fun IncognitoInput(
    content: @Composable () -> Unit,
)
