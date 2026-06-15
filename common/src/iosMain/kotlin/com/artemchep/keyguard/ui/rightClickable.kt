package com.artemchep.keyguard.ui

import androidx.compose.ui.Modifier

actual fun Modifier.rightClickable(
    onClick: (() -> Unit)?,
): Modifier = this
