package com.artemchep.keyguard.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.resolveDefaults
import com.halilibo.richtext.ui.string.RichTextStringStyle

@Composable
fun MarkdownText(
    modifier: Modifier = Modifier,
    markdown: String,
) {
    val richTextStyle = getRichTextStyle()
    RichText(
        modifier = modifier,
        style = richTextStyle,
    ) {
        Markdown(markdown)
    }
}

@Composable
fun MarkdownText(
    modifier: Modifier = Modifier,
    markdown: AstNode,
) {
    val richTextStyle = getRichTextStyle()
    RichText(
        modifier = modifier,
        style = richTextStyle,
    ) {
        BasicMarkdown(markdown)
    }
}

@Composable
private fun getRichTextStyle(): RichTextStyle {
    return RichTextStyle().resolveDefaults().copy(
        stringStyle = RichTextStringStyle().copy(
            linkStyle = MaterialTheme.typography.bodyLarge.toSpanStyle().copy(
                color = MaterialTheme.colorScheme.primary,
            ),
        ),
    )
}
