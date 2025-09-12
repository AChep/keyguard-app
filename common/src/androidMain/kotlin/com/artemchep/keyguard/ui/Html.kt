package com.artemchep.keyguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration

@Composable
actual fun HtmlText(
    modifier: Modifier,
    html: String,
) {
    de.charlex.compose.material3.HtmlText(
        modifier = modifier,
        urlSpanStyle = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
        text = html,
    )
}
