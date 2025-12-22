package com.artemchep.keyguard.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.surface.LocalSurfaceElevation
import com.artemchep.keyguard.ui.surface.SurfaceElevation

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
        SheetPopup(
            expanded = visible,
            onDismissRequest = onDismissRequest,
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
                    // Because the modal bottom sheet component provides its
                    // own background color we have to hardcode the surface
                    // elevation to provide consistent styling for the items.
                    val surfaceElevation = SurfaceElevation(
                        from = 0f,
                        to = 0.5f,
                    )
                    CompositionLocalProvider(
                        LocalSurfaceElevation provides surfaceElevation,
                    ) {
                        val contentPadding = PaddingValues(0.dp)
                        content(contentPadding)
                    }
                },
            )
        }
    }
}
