package com.artemchep.keyguard.feature.favicon

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun FaviconImage(
    imageModel: () -> Any?,
    modifier: Modifier = Modifier,
)
