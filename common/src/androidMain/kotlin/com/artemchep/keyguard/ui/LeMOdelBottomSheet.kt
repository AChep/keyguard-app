package com.artemchep.keyguard.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

@Composable
actual fun LeMOdelBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val isLandscape = run {
        val configuration = LocalConfiguration.current
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    if (isLandscape) {
        WunderPopup(
            expanded = visible,
            onDismissRequest = onDismissRequest,
            modifier = Modifier,
        ) {
            val contentPadding = PaddingValues(0.dp)
            content(contentPadding)
        }
    } else {
        val bottomSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = false,
        )
        if (visible) {
            ModalBottomSheet(
                onDismissRequest = onDismissRequest,
                sheetState = bottomSheetState,
                content = {
                    val contentPadding = PaddingValues(0.dp)
                    content(contentPadding)
                },
            )
        }
    }
}
