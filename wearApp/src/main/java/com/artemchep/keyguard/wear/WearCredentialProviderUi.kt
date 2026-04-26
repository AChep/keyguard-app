package com.artemchep.keyguard.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.android.CredentialErrorAdvanced
import com.artemchep.keyguard.android.PasswordProviderGetActivityArgs
import com.artemchep.keyguard.android.PasskeyProviderGetActivityArgs
import com.artemchep.keyguard.android.UiStateError
import com.artemchep.keyguard.android.UserVerificationState
import com.artemchep.keyguard.android.produceUserVerificationState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.yubikey.YubiKeyPromptEffect
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.close
import com.artemchep.keyguard.res.passkey_auth_via_header
import com.artemchep.keyguard.res.provider_2fa_yubikey
import com.artemchep.keyguard.res.setup_checkbox_biometric_auth
import com.artemchep.keyguard.res.userverification_button_go
import com.artemchep.keyguard.res.userverification_header_text
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.KeyguardYubiKey
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldColumn
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearCredentialErrorScreen(
    data: UiStateError,
) {
    WearScaffoldScreen(
        title = data.title,
    ) { transformationSpec ->
        item("message") {
            WearListLabel(
                modifier = Modifier
                    .transformedHeight(this, transformationSpec),
                text = data.message,
                transformation = SurfaceTransformation(transformationSpec),
            )
        }
        data.advanced?.let { advanced ->
            item("advanced_note") {
                WearListLabel(
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec),
                    text = advanced.note,
                    transformation = SurfaceTransformation(transformationSpec),
                )
            }
            advanced.action?.let { action ->
                item("advanced_action") {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        onClick = action.onClick,
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text(text = action.title)
                    }
                }
            }
        }
        item("close") {
            FilledTonalButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                onClick = data.onFinish,
                transformation = SurfaceTransformation(transformationSpec),
            ) {
                Text(text = stringResource(Res.string.close))
            }
        }
    }
}

@Composable
fun WearUserVerificationScreen(
    args: PasskeyProviderGetActivityArgs,
    onAuthenticated: () -> Unit,
) {
    WearUserVerificationScreen(
        subtitle = buildString {
            append(args.credUserDisplayName)
            append("\n@")
            append(args.credRpId)
        },
        onAuthenticated = onAuthenticated,
    )
}

@Composable
fun WearUserVerificationScreen(
    args: PasswordProviderGetActivityArgs,
    onAuthenticated: () -> Unit,
) {
    WearUserVerificationScreen(
        subtitle = args.id,
        onAuthenticated = onAuthenticated,
    )
}

@Composable
private fun WearUserVerificationScreen(
    subtitle: String,
    onAuthenticated: () -> Unit,
) {
    val state = produceUserVerificationState(
        onAuthenticated = onAuthenticated,
    )
    val content = state.content.getOrNull()

    content?.let {
        BiometricPromptEffect(it.sideEffects.showBiometricPromptFlow)
        YubiKeyPromptEffect(it.sideEffects.showYubiKeyPromptFlow)
    }

    WearScaffoldColumn(
        title = stringResource(Res.string.passkey_auth_via_header),
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = subtitle,
            textAlign = TextAlign.Center,
        )
        ExpandedIfNotEmpty(content) {
            Spacer16()
            Text(
                modifier = Modifier
                    .fillMaxWidth(),
                text = stringResource(Res.string.userverification_header_text),
                textAlign = TextAlign.Center,
            )
            Spacer16()
            WearUserVerificationContent(
                content = it,
            )
        }
    }
}

@Composable
private fun WearUserVerificationContent(
    content: UserVerificationState.Content,
) {
    val requester = remember {
        FocusRequester2()
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val updatedOnVerify by rememberUpdatedState(content.onVerify)
    val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
        if (updatedOnVerify != null) {
            {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                updatedOnVerify?.invoke()
            }
        } else {
            null
        }
    PasswordFlatTextField(
        modifier = Modifier
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
    Spacer16()
    Button(
        modifier = Modifier
            .fillMaxWidth(),
        enabled = content.onVerify != null,
        onClick = {
            updatedOnVerify?.invoke()
        },
    ) {
        Text(
            text = stringResource(Res.string.userverification_button_go),
        )
    }
    ExpandedIfNotEmpty(
        valueOrNull = Unit.takeIf {
            content.biometric != null || content.yubiKey != null
        },
    ) {
        Spacer16()
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            val onBiometricClick by rememberUpdatedState(content.biometric?.onClick)
            val onYubiKeyClick by rememberUpdatedState(content.yubiKey?.onClick)
            content.biometric?.let {
                OutlinedButton(
                    onClick = {
                        onBiometricClick?.invoke()
                    },
                    enabled = it.onClick != null,
                ) {
                    Text(stringResource(Res.string.setup_checkbox_biometric_auth))
                }
            }
            content.yubiKey?.let {
                OutlinedButton(
                    onClick = {
                        onYubiKeyClick?.invoke()
                    },
                    enabled = it.onClick != null,
                ) {
                    Text(stringResource(Res.string.provider_2fa_yubikey))
                }
            }
        }
    }
    LaunchedFocus(requester, content)
}

@Composable
private fun LaunchedFocus(
    requester: FocusRequester2,
    content: UserVerificationState.Content,
) {
    androidx.compose.runtime.LaunchedEffect(requester, content.biometric, content.yubiKey) {
        delay(80L)
        if (content.biometric == null && content.yubiKey == null) {
            requester.requestFocus()
        }
    }
}

@Composable
private fun Spacer16() {
    androidx.compose.foundation.layout.Spacer(
        modifier = Modifier
            .height(16.dp),
    )
}
