package com.artemchep.keyguard.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun LeMOdelBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    WunderPopup(
        expanded = visible,
        onDismissRequest = onDismissRequest,
        modifier = Modifier,
    ) {
        val contentPadding = PaddingValues(0.dp)
        content(contentPadding)
    }
}
