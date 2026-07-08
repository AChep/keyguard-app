package com.artemchep.keyguard.feature.gpgagent.tools.publickey

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.gpg_tools_pasted_public_key_placeholder
import com.artemchep.keyguard.res.gpg_tools_public_key_description
import com.artemchep.keyguard.res.gpg_tools_public_key_title
import com.artemchep.keyguard.res.ok
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun GpgToolsPublicKeyScreen(
    args: GpgToolsPublicKeyRoute.Args,
    transmitter: RouteResultTransmitter<GpgToolsPublicKeyResult>,
) {
    var text by remember {
        mutableStateOf(args.publicKey)
    }
    val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)

    fun deliver(result: GpgToolsPublicKeyResult) {
        transmitter(result)
        updatedNavigationController.queue(NavigationIntent.Pop)
    }

    Dialog(
        icon = icon(Icons.Outlined.Key),
        title = {
            Text(stringResource(Res.string.gpg_tools_public_key_title))
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    text = stringResource(Res.string.gpg_tools_public_key_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
                FlatTextField(
                    modifier = Modifier
                        .padding(
                            top = 16.dp,
                            start = Dimens.horizontalPadding,
                            end = Dimens.horizontalPadding,
                        ),
                    placeholder = stringResource(Res.string.gpg_tools_pasted_public_key_placeholder),
                    value = TextFieldModel(
                        text = text,
                        onChange = { text = it },
                    ),
                    maxLines = 12,
                    clearButton = true,
                )
            }
        },
        contentScrollable = false,
        actions = {
            TextButton(
                onClick = {
                    deliver(GpgToolsPublicKeyResult.Deny)
                },
            ) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(
                onClick = {
                    deliver(GpgToolsPublicKeyResult.Confirm(text.trim()))
                },
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
    )
}
