package com.artemchep.keyguard.feature.gpgkey.replacement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.gpg_user_id_replacement_value_confirm
import com.artemchep.keyguard.res.gpg_user_id_replacement_value_label
import com.artemchep.keyguard.res.gpg_user_id_replacement_value_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_value_title
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

@Composable
fun GpgUserIdReplacementScreen(
    args: GpgUserIdReplacementRoute.Args,
    transmitter: RouteResultTransmitter<String>,
) {
    val state = gpgUserIdReplacementState(
        args = args,
        transmitter = transmitter,
    )
    val updatedOnDeny by rememberUpdatedState(state.onDeny)
    val updatedOnConfirm by rememberUpdatedState(state.onConfirm)

    Dialog(
        title = {
            Text(
                text = stringResource(Res.string.gpg_user_id_replacement_value_title),
            )
        },
        content = {
            Column {
                Text(
                    modifier = Modifier.padding(horizontal = Dimens.horizontalPadding),
                    text = stringResource(Res.string.gpg_user_id_replacement_value_message),
                )
                Spacer(modifier = Modifier.height(16.dp))
                FlatTextField(
                    modifier = Modifier.padding(horizontal = Dimens.horizontalPadding),
                    testTag = "field:gpg_user_id_replacement",
                    label = stringResource(Res.string.gpg_user_id_replacement_value_label),
                    value = state.value,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            updatedOnConfirm?.invoke()
                        },
                    ),
                    singleLine = true,
                    maxLines = 1,
                )
            }
        },
        actions = {
            TextButton(
                enabled = updatedOnDeny != null,
                onClick = {
                    updatedOnDeny?.invoke()
                },
            ) {
                Text(stringResource(Res.string.cancel))
            }
            TextButton(
                enabled = updatedOnConfirm != null,
                onClick = {
                    updatedOnConfirm?.invoke()
                },
            ) {
                Text(stringResource(Res.string.gpg_user_id_replacement_value_confirm))
            }
        },
    )
}
