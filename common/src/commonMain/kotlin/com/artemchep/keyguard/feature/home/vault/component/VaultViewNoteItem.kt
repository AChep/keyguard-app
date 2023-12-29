package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.VisibilityIcon
import com.artemchep.keyguard.ui.markdown.Md
import com.artemchep.keyguard.ui.theme.Dimens
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun VaultViewNoteItem(
    modifier: Modifier = Modifier,
    item: VaultViewItem.Note,
) {
    Column(
        modifier = modifier,
    ) {
        val updatedVerify by rememberUpdatedState(item.verify)
        val visibilityState = remember(
            item.conceal,
        ) { mutableStateOf(!item.conceal) }

        if (item.conceal) {
            FlatItem(
                leading = {
                    VisibilityIcon(
                        visible = visibilityState.value,
                    )
                },
                title = {
                    val text = if (visibilityState.value) {
                        stringResource(Res.strings.hide_secure_note)
                    } else {
                        stringResource(Res.strings.reveal_secure_note)
                    }
                    Text(
                        text = text,
                    )
                },
                onClick = {
                    val shouldBeConcealed = !visibilityState.value
                    val verify = updatedVerify
                    if (
                        verify != null &&
                        shouldBeConcealed
                    ) {
                        verify.invoke {
                            visibilityState.value = true
                        }
                        return@FlatItem
                    }

                    visibilityState.value = shouldBeConcealed
                },
            )
        }
        ExpandedIfNotEmpty(
            Unit.takeIf { visibilityState.value },
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            VaultViewNoteItemLayout(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                SelectionContainer {
                    if (item.markdown) {
                        Md(
                            markdown = item.text,
                        )
                    } else {
                        Text(item.text)
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
//    Surface(
//        modifier = Modifier
//            .padding(
//                horizontal = 8.dp,
//                vertical = 2.dp,
//            )
//            .padding(top = 8.dp)
//            .fillMaxWidth(),
//        shape = MaterialTheme.shapes.medium,
//        color = MaterialTheme.colorScheme.noteContainer,
//    ) {
    Column(
        modifier = modifier
            .padding(
                vertical = 12.dp,
                horizontal = Dimens.horizontalPadding,
            ),
    ) {
        content()
    }
//    }
}
