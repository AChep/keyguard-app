package com.artemchep.keyguard.wear.feature.vault.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.ui.markdown.MarkdownText
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import com.artemchep.keyguard.wear.ui.surfaceTransformation

@Composable
fun WearVaultViewNoteItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Note,
    transformation: SurfaceTransformation? = null,
) {
    Box(
        modifier = modifier
            .surfaceTransformation(transformation)
            .padding(
                horizontal = 16.dp,
                vertical = 4.dp,
            ),
    ) {
        ProxyMaterial3Styles {
            SelectionContainer {
                when (val content = item.content) {
                    is VaultViewItem.Note.Content.Markdown -> {
                        MarkdownText(
                            markdown = content.document,
                        )
                    }

                    is VaultViewItem.Note.Content.Text -> {
                        Text(content.text)
                    }
                }
            }
        }
    }
}
