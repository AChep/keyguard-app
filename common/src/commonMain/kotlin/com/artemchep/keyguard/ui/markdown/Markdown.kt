package com.artemchep.keyguard.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.ui.LinkClickHandler
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.resolveDefaults
import com.halilibo.richtext.ui.string.RichTextStringStyle
import org.kodein.di.compose.rememberInstance

@Composable
fun MarkdownText(
    modifier: Modifier = Modifier,
    markdown: String,
) {
    val richTextStyle = getRichTextStyle()
    RichText(
        modifier = modifier,
        style = richTextStyle,
        linkClickHandler = rememberGracefulLinkClickHandler(),
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
        linkClickHandler = rememberGracefulLinkClickHandler(),
    ) {
        BasicMarkdown(markdown)
    }
}

@Composable
private fun rememberGracefulLinkClickHandler(): LinkClickHandler {
    val showMessage by rememberInstance<ShowMessage>()
    val updatedUriHandler by rememberUpdatedState(LocalUriHandler.current)
    return remember {
        LinkClickHandler { uri ->
            try {
                updatedUriHandler.openUri(uri)
            } catch (e: Exception) {
                val title = e.message
                    ?: "Could not open a URI"
                val msg = ToastMessage(
                    title = title,
                    type = ToastMessage.Type.ERROR,
                )
                showMessage.copy(msg)
            }
        }
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
