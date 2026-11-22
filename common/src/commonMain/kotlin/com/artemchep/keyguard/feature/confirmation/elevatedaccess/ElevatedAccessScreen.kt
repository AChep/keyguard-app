package com.artemchep.keyguard.feature.confirmation.elevatedaccess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.yubikey.YubiKeyPromptEffect
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.KeyguardYubiKey
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun ElevatedAccessScreen(
    transmitter: RouteResultTransmitter<ElevatedAccessResult>,
) {
    val state = produceElevatedAccessState(
        transmitter = transmitter,
    )
    ElevatedAccessPromptEffects(state)

    Dialog(
        icon = icon(Icons.Outlined.Security),
        title = {
            Text(stringResource(Res.string.elevatedaccess_header_title))
        },
        content = {
            Column(
                modifier = Modifier,
            ) {
                ElevatedAccessForm(
                    state = state,
                )
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

/**
 * Shows the biometric and YubiKey prompts of the access confirmation
 * [state], once its content is ready.
 */
@Composable
fun ElevatedAccessPromptEffects(
    state: ElevatedAccessState,
) {
    val content = state.content
    if (content is Loadable.Ok) {
        BiometricPromptEffect(content.value.sideEffects.showBiometricPromptFlow)
        YubiKeyPromptEffect(content.value.sideEffects.showYubiKeyPromptFlow)
    }
}

/**
 * The form of the access confirmation: the password field, the biometric
 * and YubiKey buttons. Pair it with [ElevatedAccessPromptEffects].
 *
 * @param onConfirm What the Go key of the password field does.
 * @param showText `false` if the host already explains why it asks.
 * @param alwaysFocusPassword Focuses the password field even if biometrics
 *   or a YubiKey can confirm the access; otherwise only if neither can.
 */
@Composable
fun ColumnScope.ElevatedAccessForm(
    state: ElevatedAccessState,
    onConfirm: (() -> Unit)? = state.onConfirm,
    showText: Boolean = true,
    alwaysFocusPassword: Boolean = false,
) {
    when (val content = state.content) {
        is Loadable.Loading -> {
            ElevatedAccessSkeleton(
                showText = showText,
            )
        }

        is Loadable.Ok -> {
            ElevatedAccessContent(
                content = content.value,
                onConfirm = onConfirm,
                showText = showText,
                alwaysFocusPassword = alwaysFocusPassword,
            )
        }
    }
}

@Composable
private fun ColumnScope.ElevatedAccessSkeleton(
    showText: Boolean,
) {
    if (showText) {
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
    }
    SkeletonTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.ElevatedAccessContent(
    content: ElevatedAccessState.Content,
    onConfirm: (() -> Unit)?,
    showText: Boolean,
    alwaysFocusPassword: Boolean,
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
    if (showText) {
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.elevatedaccess_header_text),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
    PasswordFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .focusRequester2(requester),
        testTag = "field:password",
        value = content.password,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = keyboardOnGo,
        ),
    )
    val onBiometricButtonClick by rememberUpdatedState(content.biometric?.onClick)
    val onYubiKeyButtonClick by rememberUpdatedState(content.yubiKey?.onClick)
    ExpandedIfNotEmpty(
        modifier = Modifier
            .align(Alignment.CenterHorizontally),
        valueOrNull = Unit.takeIf {
            content.biometric != null || content.yubiKey != null
        },
    ) {
        Row(
            modifier = Modifier
                .padding(
                    top = 24.dp,
                    bottom = 8.dp, // fix shadow clipping
                ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (content.biometric != null) {
                Button(
                    enabled = content.biometric.onClick != null,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    elevation = null,
                    border = ButtonDefaults.outlinedButtonBorder(
                        enabled = content.biometric.onClick != null,
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
            if (content.yubiKey != null) {
                Button(
                    enabled = content.yubiKey.onClick != null,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    elevation = null,
                    border = ButtonDefaults.outlinedButtonBorder(
                        enabled = content.yubiKey.onClick != null,
                    ),
                    onClick = {
                        onYubiKeyButtonClick?.invoke()
                    },
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.KeyguardYubiKey,
                        contentDescription = null,
                    )
                }
            }
        }
    }

    LaunchedEffect(requester) {
        delay(80L)
        // Do not auto-focus the password field if a device has hardware
        // confirmation available. It causes a weird layout lags when keyboard
        // pops up for a split second.
        if (alwaysFocusPassword || content.biometric == null && content.yubiKey == null) {
            requester.requestFocus()
        }
    }
}
