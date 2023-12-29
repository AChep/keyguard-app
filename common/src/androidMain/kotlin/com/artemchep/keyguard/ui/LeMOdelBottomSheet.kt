package com.artemchep.keyguard.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
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
            val contentInsets = WindowInsets.systemBars
                .only(WindowInsetsSides.Bottom)
            val contentPadding = contentInsets
                .asPaddingValues()
            ModalBottomSheet(
                onDismissRequest = onDismissRequest,
                sheetState = bottomSheetState,
                windowInsets = WindowInsets.systemBars
                    .only(WindowInsetsSides.Top),
                content = {
                    content(contentPadding)
                },
            )
        }
    }
}
