package com.artemchep.keyguard.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun FaviconIcon(
    imageModel: () -> Any?,
    modifier: Modifier = Modifier,
) {
    AsyncIcon(
        imageModel = imageModel,
        modifier = modifier,
        contentDescription = "Favicon",
        errorImageVector = Icons.Outlined.KeyguardWebsite,
    )
}
