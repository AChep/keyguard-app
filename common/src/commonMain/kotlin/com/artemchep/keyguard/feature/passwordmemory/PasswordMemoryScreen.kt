package com.artemchep.keyguard.feature.passwordmemory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.close
import com.artemchep.keyguard.res.password_action_test_memory_title
import com.artemchep.keyguard.res.password_memory_test_note
import com.artemchep.keyguard.res.verify
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PasswordMemoryScreen(
    args: PasswordMemoryRoute.Args,
) {
    val state = producePasswordMemoryState(
        args = args,
    )
    val updatedOnClose by rememberUpdatedState(state.onClose)
    val updatedOnVerify by rememberUpdatedState(state.onVerify)

    Dialog(
        icon = icon(Icons.Outlined.Psychology),
        title = {
            Text(
                text = stringResource(Res.string.password_action_test_memory_title),
            )
        },
        content = {
            Column {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    text = stringResource(Res.string.password_memory_test_note),
                )
                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )
                PasswordFlatTextField(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    testTag = "field:password",
                    value = state.password,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            updatedOnVerify?.invoke()
                        },
                    ),
                )
            }
        },
        actions = {
            TextButton(
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
            TextButton(
                enabled = updatedOnVerify != null,
                onClick = {
                    updatedOnVerify?.invoke()
                },
            ) {
                Text(stringResource(Res.string.verify))
            }
        },
    )
}
