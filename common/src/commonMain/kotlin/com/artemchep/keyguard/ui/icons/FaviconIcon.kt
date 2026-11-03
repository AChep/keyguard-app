package com.artemchep.keyguard.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun FaviconIcon(
    imageModel: () -> Any?,
    modifier: Modifier = Modifier,
    onError: (() -> Unit)? = null,
) {
    AsyncIcon(
        imageModel = imageModel,
        modifier = modifier,
        // The icon is decorative: the text
        // next to it names the website.
        contentDescription = null,
        errorImageVector = Icons.Outlined.KeyguardWebsite,
        onError = onError,
    )
}
