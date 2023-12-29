package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable

@Composable
fun Compose(
    content: @Composable () -> Unit,
) {
    content()
}

inline fun composable(
    crossinline content: @Composable () -> Unit,
): @Composable () -> Unit = {
    content()
}
