package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
actual fun LeMOdelBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (visible) {
        content(PaddingValues(0.dp))
    }
}
