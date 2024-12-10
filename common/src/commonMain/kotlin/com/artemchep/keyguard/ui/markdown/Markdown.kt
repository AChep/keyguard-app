package com.artemchep.keyguard.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextLinkStyles
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.resolveDefaults
import com.halilibo.richtext.ui.string.RichTextStringStyle
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.kodein.di.compose.rememberInstance

@Composable
fun MarkdownText(
    modifier: Modifier = Modifier,
    markdown: String,
) {
    ProvideGracefulUriHandler {
        val richTextStyle = getRichTextStyle()
        RichText(
            modifier = modifier,
            style = richTextStyle,
        ) {
            Markdown(markdown)
        }
    }
}

@Composable
fun MarkdownText(
    modifier: Modifier = Modifier,
    markdown: AstNode,
) {
    ProvideGracefulUriHandler {
        val richTextStyle = getRichTextStyle()
        RichText(
            modifier = modifier,
            style = richTextStyle,
        ) {
            BasicMarkdown(markdown)
        }
    }
}

@Composable
private fun ProvideGracefulUriHandler(
    content: @Composable () -> Unit,
) {
    val showMessage by rememberInstance<ShowMessage>()

    val updatedContext by rememberUpdatedState(LocalLeContext)
    val updatedUriHandler by rememberUpdatedState(LocalUriHandler.current)

    val uriHandler = remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                try {
                    updatedUriHandler.openUri(uri)
                } catch (e: Exception) {
                    GlobalScope.launch {
                        val title = textResource(Res.string.error_failed_open_uri, updatedContext)
                        val msg = ToastMessage(
                            title = title,
                            text = e.message,
                            type = ToastMessage.Type.ERROR,
                        )
                        showMessage.copy(msg)
                    }
                }
            }
        }
    }

    return CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        content()
    }
}

@Composable
private fun getRichTextStyle(): RichTextStyle {
    return RichTextStyle().resolveDefaults().copy(
        stringStyle = RichTextStringStyle(
            linkStyle = TextLinkStyles(
                style = MaterialTheme.typography.bodyLarge.toSpanStyle().copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
            ),
        ),
    )
}
