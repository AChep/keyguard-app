package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.home.vault.model.Visibility
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.VisibilityIcon
import com.artemchep.keyguard.ui.markdown.MarkdownText
import com.artemchep.keyguard.ui.theme.Dimens
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultViewNoteItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Note,
) {
    val visibilityConfig = item.visibility
    val visibilityState = rememberVisibilityState(
        visibilityConfig,
    )
    Column(
        modifier = modifier,
    ) {
        if (!visibilityState.value.value) {
            val updatedVisibilityConfig by rememberUpdatedState(visibilityConfig)
            FlatItemSimpleExpressive(
                leading = {
                    VisibilityIcon(
                        visible = visibilityState.value.value,
                    )
                },
                title = {
                    val text = if (visibilityState.value.value) {
                        stringResource(Res.string.hide_secure_note)
                    } else {
                        stringResource(Res.string.reveal_secure_note)
                    }
                    Text(
                        text = text,
                    )
                },
                onClick = {
                    updatedVisibilityConfig.transformUserEvent(!visibilityState.value.value) { newValue ->
                        visibilityState.value = Visibility.Event(
                            value = newValue,
                            timestamp = Clock.System.now(),
                        )
                    }
                },
            )
        }
        ExpandedIfNotEmpty(
            Unit.takeIf { visibilityState.value.value },
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            VaultViewNoteItemLayout(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                SelectionContainer {
                    when (item.content) {
                        is VaultViewItem.Note.Content.Markdown -> {
                            MarkdownText(
                                markdown = item.content.node,
                            )
                        }
                        is VaultViewItem.Note.Content.Text -> {
                            Text(item.content.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VaultViewNoteItemLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .padding(
                vertical = Dimens.contentPadding
                    .coerceAtLeast(16.dp),
                horizontal = Dimens.textHorizontalPadding,
            ),
    ) {
        content()
    }
}
