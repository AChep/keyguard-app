package com.artemchep.keyguard.ui

import androidx.compose.ui.Modifier

expect fun Modifier.rightClickable(
    onClick: (() -> Unit)?,
): Modifier
