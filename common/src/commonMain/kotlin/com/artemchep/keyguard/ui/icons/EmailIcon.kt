package com.artemchep.keyguard.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.favicon.GravatarUrl

@Composable
expect fun EmailIcon(
    modifier: Modifier = Modifier,
    gravatarUrl: GravatarUrl?,
)
