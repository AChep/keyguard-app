package com.artemchep.keyguard.feature.confirmation.tags

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.tags.TagsField
import com.artemchep.keyguard.ui.theme.Dimens
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
            TagsField(
                modifier = Modifier
                    .padding(horizontal = Dimens.fieldHorizontalPadding),
                value = state.tags,
            )
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
