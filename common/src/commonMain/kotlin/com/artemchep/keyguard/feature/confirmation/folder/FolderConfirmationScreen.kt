package com.artemchep.keyguard.feature.confirmation.folder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.search.filter.component.FilterItemComposable
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FolderConfirmationScreen(
    args: FolderConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<FolderConfirmationResult>,
) {
    val state = folderConfirmationState(
        args = args,
        transmitter = transmitter,
    )
    Dialog(
        icon = icon(Icons.Outlined.Folder),
        title = {
            Text(stringResource(Res.string.folderpicker_header_title))
        },
        content = {
            val data = state.content.getOrNull()
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart),
            ) {
                FlowRow(
                    modifier = Modifier
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (data != null) {
                        if (data.items.isEmpty()) {
                            EmptyView()
                        }

                        data.items.forEach { item ->
                            key(item.key) {
                                FolderConfirmationItem(
                                    modifier = Modifier,
                                    item = item,
                                )
                            }
                        }
                    }
                }
                val field = state.content.getOrNull()?.new
                CreateNewFolder(
                    field = field,
                )
            }

            if (state.content is Loadable.Loading && data == null) {
                KeyguardLoadingIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    contained = true,
                )
            }
        },
        actions = {
            val updatedOnDeny by rememberUpdatedState(state.onDeny)
            val updatedOnConfirm by rememberUpdatedState(state.onConfirm)
            TextButton(
                enabled = state.onDeny != null,
                onClick = {
                    updatedOnDeny?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
            TextButton(
                enabled = state.onConfirm != null,
                onClick = {
                    updatedOnConfirm?.invoke()
                },
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
    )
}

@Composable
fun CreateNewFolder(
    field: TextFieldModel2?,
    modifier: Modifier = Modifier,
) {
    ExpandedIfNotEmpty(
        modifier = modifier,
        valueOrNull = field,
    ) {
        Column {
            val requester = remember {
                FocusRequester2()
            }

            HorizontalDivider(
                modifier = Modifier
                    .padding(vertical = 16.dp),
            )
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.horizontalPadding),
                text = stringResource(Res.string.folderpicker_create_new_folder),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
            Spacer(Modifier.height(16.dp))
            FlatTextField(
                modifier = Modifier
                    .padding(horizontal = 8.dp),
                fieldModifier = Modifier
                    .focusRequester2(requester),
                value = it,
                label = stringResource(Res.string.generic_name),
            )

            LaunchedEffect(requester) {
                delay(80L)
                requester.requestFocus()
            }
        }
    }
}

@Composable
private fun FolderConfirmationItem(
    modifier: Modifier = Modifier,
    item: FolderConfirmationState.Content.Item,
) {
    FilterItemComposable(
        modifier = modifier,
        checked = item.selected,
        leading =
        if (item.icon != null) {
            // composable
            {
                Icon(
                    item.icon,
                    null,
                )
            }
        } else {
            null
        },
        title = item.title,
        text = null,
        onClick = item.onClick,
    )
}
