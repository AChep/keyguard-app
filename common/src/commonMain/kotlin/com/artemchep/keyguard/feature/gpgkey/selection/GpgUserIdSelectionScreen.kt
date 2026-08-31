package com.artemchep.keyguard.feature.gpgkey.selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cancel
import com.artemchep.keyguard.res.gpg_user_id_revocation_confirm
import com.artemchep.keyguard.res.gpg_user_id_revocation_dialog_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_dialog_title
import com.artemchep.keyguard.res.gpg_user_id_replacement_confirm_next
import com.artemchep.keyguard.res.gpg_user_id_replacement_dialog_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_dialog_title
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

@Composable
fun GpgUserIdSelectionScreen(
    args: GpgUserIdSelectionRoute.Args,
    transmitter: RouteResultTransmitter<String>,
) {
    val state = gpgUserIdSelectionState(
        args = args,
        transmitter = transmitter,
    )
    val revocation = args.mode == GpgUserIdSelectionRoute.Args.Mode.Revocation
    Dialog(
        title = {
            Text(
                text = stringResource(
                    if (revocation) {
                        Res.string.gpg_user_id_revocation_dialog_title
                    } else {
                        Res.string.gpg_user_id_replacement_dialog_title
                    },
                ),
            )
        },
        content = {
            GpgUserIdSelectionContent(
                state = state,
                revocation = revocation,
            )
        },
        actions = {
            GpgUserIdSelectionActions(
                state = state,
                revocation = revocation,
            )
        },
    )
}

@Composable
private fun GpgUserIdSelectionContent(
    state: GpgUserIdSelectionState,
    revocation: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(
                if (revocation) {
                    Res.string.gpg_user_id_revocation_dialog_message
                } else {
                    Res.string.gpg_user_id_replacement_dialog_message
                },
            ),
        )
        if (state.identities.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            state.identities.forEach { identity ->
                key(identity.key) {
                    GpgUserIdSelectionIdentity(identity = identity)
                }
            }
        }
        state.validationError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                modifier = Modifier.padding(horizontal = Dimens.horizontalPadding),
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GpgUserIdSelectionActions(
    state: GpgUserIdSelectionState,
    revocation: Boolean,
) {
    val updatedOnDeny by rememberUpdatedState(state.onDeny)
    val updatedOnConfirm by rememberUpdatedState(state.onConfirm)
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
        colors = if (revocation) {
            ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            )
        } else {
            ButtonDefaults.textButtonColors()
        },
        onClick = {
            updatedOnConfirm?.invoke()
        },
    ) {
        Text(
            text = stringResource(
                if (revocation) {
                    Res.string.gpg_user_id_revocation_confirm
                } else {
                    Res.string.gpg_user_id_replacement_confirm_next
                },
            ),
        )
    }
}

@Composable
private fun GpgUserIdSelectionIdentity(
    identity: GpgUserIdSelectionState.Identity,
    modifier: Modifier = Modifier,
) {
    FlatItemLayout(
        modifier = modifier,
        leading = {
            RadioButton(
                selected = identity.selected,
                onClick = null,
            )
        },
        content = {
            Text(
                text = identity.title,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = identity.onSelect,
    )
}
