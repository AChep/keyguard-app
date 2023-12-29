package com.artemchep.keyguard.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.rightClickable(
    onClick: (() -> Unit)?,
) = this
