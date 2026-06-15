package com.artemchep.keyguard.feature.confirmation.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.TagFlatTextField
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun TagsConfirmationScreen(
    args: TagsConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<TagsConfirmationResult>,
) {
    val state = tagsConfirmationState(
        args = args,
        transmitter = transmitter,
    )
    Dialog(
        icon = icon(Icons.AutoMirrored.Outlined.Label),
        title = {
            Text(stringResource(Res.string.ciphers_action_change_tags_title))
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                if (state.items.isEmpty()) {
                    Text(
                        modifier = Modifier
                            .padding(horizontal = Dimens.horizontalPadding),
                        text = stringResource(Res.string.tag_none),
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.items.forEach { item ->
                        key(item.key) {
                            TagItem(
                                modifier = Modifier,
                                item = item,
                            )
                        }
                    }
                }
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
                TextButton(
                    modifier = Modifier
                        .padding(horizontal = Dimens.buttonHorizontalPadding),
                    onClick = {
                        state.onAdd?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(Dimens.buttonIconPadding))
                    Text(
                        text = stringResource(Res.string.list_add),
                    )
                }
            }
        },
        actions = {
            val updatedOnDeny = rememberUpdatedState(state.onDeny)
            val updatedOnConfirm = rememberUpdatedState(state.onConfirm)
            TextButton(
                enabled = state.onDeny != null,
                onClick = {
                    updatedOnDeny.value?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
            TextButton(
                enabled = state.onConfirm != null,
                onClick = {
                    updatedOnConfirm.value?.invoke()
                },
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
    )
}

@Composable
private fun TagItem(
    modifier: Modifier = Modifier,
    item: TagsConfirmationState.Item,
) {
    TagFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        shapeState = ShapeState.ALL,
        value = item.field,
        trailing = {
            IconButton(
                onClick = {
                    item.onRemove?.invoke()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                )
            }
        },
    )
}
