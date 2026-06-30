package com.artemchep.keyguard.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// html-text-material3 does not publish macOS klibs; fall back to plain text.
@Composable
actual fun HtmlText(
    modifier: Modifier,
    html: String,
) {
    Text(
        modifier = modifier,
        text = html,
    )
}
