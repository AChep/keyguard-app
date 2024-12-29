package com.artemchep.keyguard.ui.tooltip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun <T : Any> Tooltip(
    modifier: Modifier,
    valueOrNull: T?,
    tooltip: @Composable BoxScope.(T) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier,
    ) {
        content()
    }
}
