package com.artemchep.keyguard.ui.icons

import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun VisibilityIcon(
    modifier: Modifier = Modifier,
    visible: Boolean,
) {
    val targetImage = if (visible) {
        Icons.Outlined.Visibility
    } else {
        Icons.Outlined.VisibilityOff
    }
    Crossfade(
        modifier = modifier,
        targetState = targetImage,
    ) { image ->
        Icon(
            imageVector = image,
            contentDescription = null,
        )
    }
}
