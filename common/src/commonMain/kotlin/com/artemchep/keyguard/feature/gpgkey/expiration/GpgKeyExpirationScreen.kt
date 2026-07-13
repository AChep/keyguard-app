package com.artemchep.keyguard.feature.gpgkey.expiration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.search.filter.component.FilterChipItemComposable
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GpgKeyExpirationScreen(
    args: GpgKeyExpirationRoute.Args,
    transmitter: RouteResultTransmitter<GpgKeyExpirationChange>,
) {
    val state = gpgKeyExpirationState(
        args = args,
        transmitter = transmitter,
    )
    Dialog(
        title = {
            Text(stringResource(Res.string.gpg_key_expiry_dialog_title))
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = Dimens.horizontalPadding),
                    text = stringResource(Res.string.gpg_key_expiry_dialog_message),
                )
                Spacer(modifier = Modifier.height(16.dp))
                FlowRow(
                    modifier = Modifier.padding(horizontal = Dimens.horizontalPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.presets.forEach { preset ->
                        key(preset.key) {
                            FilterChipItemComposable(
                                checked = preset.selected,
                                leading = null,
                                title = preset.title,
                                text = preset.text,
                                onClick = preset.onClick,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.components.forEach { component ->
                        key(component.key) {
                            GpgKeyExpirationComponent(component)
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
                Text(stringResource(Res.string.cancel))
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
private fun GpgKeyExpirationComponent(
    component: GpgKeyExpirationState.Component,
) {
    FlatItemLayout(
        leading = {
            Checkbox(
                checked = component.selected,
                onCheckedChange = null,
            )
        },
        content = {
            Column {
                Text(
                    text = component.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = component.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.combineAlpha(MediumEmphasisAlpha),
                )
            }
        },
        onClick = component.onToggle,
    )
}
