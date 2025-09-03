package com.artemchep.keyguard.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.favicon.GravatarUrl

@Composable
fun EmailIcon(
    modifier: Modifier = Modifier,
    gravatarUrl: GravatarUrl?,
) {
    AsyncIcon(
        imageModel = { gravatarUrl },
        modifier = modifier,
        contentDescription = "Email",
        errorImageVector = Icons.Outlined.Email,
    )
}
