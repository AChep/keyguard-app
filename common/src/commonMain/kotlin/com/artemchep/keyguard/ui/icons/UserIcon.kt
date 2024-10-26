package com.artemchep.keyguard.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.favicon.PictureUrl

@Composable
expect fun UserIcon(
    modifier: Modifier = Modifier,
    pictureUrl: PictureUrl?,
)
