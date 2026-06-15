package com.artemchep.keyguard.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

expect class MarkdownDocument

expect class MarkdownParser() {
    fun parse(markdown: String): MarkdownDocument
}

@Composable
fun MarkdownText(
    modifier: Modifier = Modifier,
    markdown: String,
) {
    PlatformMarkdownText(
        modifier = modifier,
        markdown = markdown,
    )
}

@Composable
fun MarkdownText(
    modifier: Modifier = Modifier,
    markdown: MarkdownDocument,
) {
    PlatformMarkdownText(
        modifier = modifier,
        markdown = markdown,
    )
}

@Composable
internal expect fun PlatformMarkdownText(
    modifier: Modifier,
    markdown: String,
)

@Composable
internal expect fun PlatformMarkdownText(
    modifier: Modifier,
    markdown: MarkdownDocument,
)
