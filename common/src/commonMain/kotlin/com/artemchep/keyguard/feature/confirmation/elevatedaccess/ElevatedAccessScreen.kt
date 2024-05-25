package com.artemchep.keyguard.feature.confirmation.elevatedaccess

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.util.DividerColor
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay

@Composable
fun ElevatedAccessScreen(
    transmitter: RouteResultTransmitter<ElevatedAccessResult>,
) {
    val state = produceElevatedAccessState(
        transmitter = transmitter,
    )
    val content = state.content
    // If we have a content ready, subscribe to biometric
    // prompt effects.
    if (content is Loadable.Ok) {
        BiometricPromptEffect(content.value.sideEffects.showBiometricPromptFlow)
    }

    Dialog(
        icon = icon(Icons.Outlined.Security),
        title = {
            Text(stringResource(Res.string.elevatedaccess_header_title))
        },
        content = {
            Column(
                modifier = Modifier,
            ) {
                when (content) {
                    is Loadable.Loading -> {
                        ElevatedAccessSkeleton()
                    }

                    is Loadable.Ok -> {
                        ElevatedAccessContent(
                            content = content.value,
                            onConfirm = state.onConfirm,
                        )
                    }
                }
            }
        },
        actions = {
            val updatedOnDeny by rememberUpdatedState(state.onDeny)
            val updatedOnConfirm by rememberUpdatedState(state.onConfirm)
            TextButton(
                modifier = Modifier
                    .testTag("btn:close"),
                enabled = state.onDeny != null,
                onClick = {
                    updatedOnDeny?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
            TextButton(
                modifier = Modifier
                    .testTag("btn:go"),
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
private fun ColumnScope.ElevatedAccessSkeleton() {
    SkeletonText(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .fillMaxWidth(0.8f),
    )
    SkeletonText(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .fillMaxWidth(0.6f),
    )
    Spacer(modifier = Modifier.height(16.dp))
    SkeletonTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.ElevatedAccessContent(
    content: ElevatedAccessState.Content,
    onConfirm: (() -> Unit)?,
) {
    val requester = remember {
        FocusRequester2()
    }

    val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
        if (onConfirm != null) {
            // lambda
            {
                onConfirm.invoke()
            }
        } else {
            null
        }
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        text = stringResource(Res.string.elevatedaccess_header_text),
    )
    Spacer(modifier = Modifier.height(16.dp))
    PasswordFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .focusRequester2(requester),
        testTag = "field:password",
        value = content.password,
        keyboardOptions = KeyboardOptions(
            imeAction = when {
                keyboardOnGo != null -> ImeAction.Go
                else -> ImeAction.Default
            },
        ),
        keyboardActions = KeyboardActions(
            onGo = keyboardOnGo,
        ),
    )
    val onBiometricButtonClick by rememberUpdatedState(content.biometric?.onClick)
    ExpandedIfNotEmpty(
        modifier = Modifier
            .align(Alignment.CenterHorizontally),
        valueOrNull = content.biometric,
    ) { b ->
        OutlinedButton(
            modifier = Modifier
                .padding(
                    top = 24.dp,
                    bottom = 16.dp, // fix shadow clipping
                ),
            enabled = b.onClick != null,
            border = BorderStroke(
                width = Dp.Hairline,
                color = DividerColor,
            ),
            onClick = {
                onBiometricButtonClick?.invoke()
            },
            contentPadding = PaddingValues(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Fingerprint,
                contentDescription = null,
            )
        }
    }

    LaunchedEffect(requester) {
        delay(80L)
        // Do not auto-focus the password field if a device has biometric
        // available. It causes a weird layout lags when keyboard pops up for
        // a split second.
        if (onBiometricButtonClick == null) {
            requester.requestFocus()
        }
    }
}
