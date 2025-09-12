package com.artemchep.keyguard.ui

import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun HtmlText(
    modifier: Modifier,
    html: String,
) {
    Text(
        modifier = modifier,
        text = html,
        color = LocalContentColor.current,
    )
}
