package com.artemchep.keyguard.feature.auth.common

import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import arrow.core.partially1
import com.artemchep.keyguard.ui.icons.VisibilityIcon

@Composable
fun VisibilityToggle(
    visibilityState: VisibilityState,
) {
    VisibilityToggle(
        visible = visibilityState.isVisible,
        enabled = visibilityState.isEnabled,
        onVisibleChange = { shouldBeVisible ->
            // Apply the visibility state only if the
            // button is enabled.
            if (visibilityState.isEnabled) {
                visibilityState.isVisible = shouldBeVisible
            }
        },
    )
}

@Composable
fun VisibilityToggle(
    visible: Boolean,
    enabled: Boolean = true,
    onVisibleChange: (Boolean) -> Unit,
) {
    IconButton(
        enabled = enabled,
        onClick = onVisibleChange.partially1(!visible),
    ) {
        VisibilityIcon(
            visible = visible,
        )
    }
}

class VisibilityState(
    isVisible: Boolean = false,
    isEnabled: Boolean = true,
) {
    var isVisible by mutableStateOf(isVisible)
    var isEnabled by mutableStateOf(isEnabled)
}
