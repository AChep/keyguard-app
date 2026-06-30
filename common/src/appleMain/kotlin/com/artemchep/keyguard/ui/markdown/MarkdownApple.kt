package com.artemchep.keyguard.ui.markdown

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual class MarkdownDocument(
    internal val text: String,
)

actual class MarkdownParser actual constructor() {
    actual fun parse(markdown: String): MarkdownDocument =
        MarkdownDocument(markdown)
}

@Composable
internal actual fun PlatformMarkdownText(
    modifier: Modifier,
    markdown: String,
) {
    Text(
        modifier = modifier,
        text = markdown,
    )
}

@Composable
internal actual fun PlatformMarkdownText(
    modifier: Modifier,
    markdown: MarkdownDocument,
) {
    PlatformMarkdownText(
        modifier = modifier,
        markdown = markdown.text,
    )
}
