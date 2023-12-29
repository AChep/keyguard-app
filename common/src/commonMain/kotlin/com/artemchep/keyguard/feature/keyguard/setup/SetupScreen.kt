package com.artemchep.keyguard.feature.keyguard.setup

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.auth.common.autofill
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.keyguard.unlock.unlockScreenActionPadding
import com.artemchep.keyguard.feature.keyguard.unlock.unlockScreenTitlePadding
import com.artemchep.keyguard.platform.isStandalone
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.AutofillButton
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.OtherScaffold
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.skeleton.SkeletonButton
import com.artemchep.keyguard.ui.skeleton.SkeletonCheckbox
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun SetupScreen(
    /**
     * Creates a vault with the key derived from a
     * given password.
     */
    createVaultWithMasterPassword: VaultState.Create.WithPassword,
    createVaultWithMasterPasswordAndBiometric: VaultState.Create.WithBiometric?,
) {
    val loadableState = setupScreenState(
        createVaultWithMasterPassword = createVaultWithMasterPassword,
        createVaultWithMasterPasswordAndBiometric = createVaultWithMasterPasswordAndBiometric,
    )
    loadableState.fold(
        ifLoading = {
            SetupScreenSkeleton()
        },
        ifOk = { state ->
            SetupScreen(state)
        },
    )
}

@Composable
private fun SetupScreenSkeleton() {
    OtherScaffold {
        SetupScreenCreateVaultTitle()
        Spacer(Modifier.height(unlockScreenTitlePadding))
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth(),
        )
        Spacer(Modifier.height(unlockScreenActionPadding))
        FlatItemLayout(
            paddingValues = PaddingValues(0.dp),
            leading = {
                SkeletonCheckbox(
                    clickable = false,
                )
            },
            content = {
                SkeletonText(
                    modifier = Modifier
                        .fillMaxWidth(0.4f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            enabled = true,
        )
        Spacer(Modifier.height(unlockScreenActionPadding))
        FlatItemLayout(
            paddingValues = PaddingValues(0.dp),
            leading = {
                SkeletonCheckbox(
                    clickable = false,
                )
            },
            content = {
                SkeletonText(
                    modifier = Modifier
                        .fillMaxWidth(0.3f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            enabled = true,
        )
        Spacer(Modifier.height(unlockScreenActionPadding))
        SkeletonButton(
            modifier = Modifier
                .fillMaxWidth(),
        )
    }
}

@Composable
fun SetupScreen(
    setupState: SetupState,
) {
    BiometricPromptEffect(setupState.sideEffects.showBiometricPromptFlow)

    OtherScaffold {
        SetupContent(
            setupState = setupState,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ColumnScope.SetupContent(
    setupState: SetupState,
) {
    val focusRequester = remember { FocusRequester() }
    // Auto focus the text field
    // on launch.
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    SetupScreenCreateVaultTitle()
    Spacer(Modifier.height(unlockScreenTitlePadding))
    val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
        if (setupState.onCreateVault != null) {
            // lambda
            {
                setupState.onCreateVault.invoke()
            }
        } else {
            null
        }
    PasswordFlatTextField(
        modifier = Modifier
            .focusRequester(focusRequester),
        fieldModifier = Modifier
            .autofill(
                value = setupState.password.state.value,
                autofillTypes = listOf(
                    AutofillType.NewPassword,
                ),
                onFill = setupState.password.onChange,
            ),
        testTag = "field:password",
        label = stringResource(Res.strings.setup_field_app_password_label),
        value = setupState.password,
        keyboardOptions = KeyboardOptions(
            imeAction = when {
                keyboardOnGo != null -> ImeAction.Go
                else -> ImeAction.Default
            },
        ),
        keyboardActions = KeyboardActions(
            onGo = keyboardOnGo,
        ),
        clearButton = false, // Minimize used space
        leading = null, // Do not display default icon
        trailing = {
            AutofillButton(
                key = "password",
                password = true,
                onValueChange = setupState.password.onChange,
            )
        },
    )
    if (setupState.biometric != null) {
        Spacer(Modifier.height(unlockScreenActionPadding))

        val updatedBiometric by rememberUpdatedState(setupState.biometric)
        FlatItemLayout(
            paddingValues = PaddingValues(0.dp),
            leading = {
                Checkbox(
                    checked = setupState.biometric.checked,
                    onCheckedChange = null,
                )
            },
            content = {
                Text(
                    text = stringResource(Res.strings.setup_checkbox_biometric_auth),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            onClick = {
                val newValue = !updatedBiometric.checked
                updatedBiometric.onChange?.invoke(newValue)
            },
            enabled = updatedBiometric.onChange != null,
        )
    }
    Spacer(Modifier.height(unlockScreenActionPadding))
    val updatedCrashlytics by rememberUpdatedState(setupState.crashlytics)
    FlatItemLayout(
        paddingValues = PaddingValues(0.dp),
        leading = {
            Checkbox(
                checked = setupState.crashlytics.checked,
                onCheckedChange = null,
            )
        },
        content = {
            Text(
                text = stringResource(Res.strings.setup_button_send_crash_reports),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = {
            val newValue = !updatedCrashlytics.checked
            updatedCrashlytics.onChange?.invoke(newValue)
        },
        enabled = updatedCrashlytics.onChange != null,
    )
    Spacer(Modifier.height(unlockScreenActionPadding))
    val onCreateButtonClick by rememberUpdatedState(setupState.onCreateVault)
    Button(
        modifier = Modifier
            .testTag("btn:go")
            .fillMaxWidth(),
        enabled = setupState.onCreateVault != null,
        onClick = {
            onCreateButtonClick?.invoke()
        },
    ) {
        Crossfade(
            modifier = Modifier
                .size(24.dp),
            targetState = setupState.isLoading,
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(
                    color = LocalContentColor.current,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                )
            }
        }
        Spacer(
            modifier = Modifier
                .width(Dimens.buttonIconPadding),
        )
        Text(
            text = stringResource(Res.strings.setup_button_create_vault),
        )
    }
}

@Composable
private fun ColumnScope.SetupScreenCreateVaultTitle() {
    Text(
        textAlign = TextAlign.Center,
        text = remember {
            keyguardSpan()
        },
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(unlockScreenTitlePadding))
    Text(
        modifier = Modifier
            .fillMaxWidth(),
        text = stringResource(Res.strings.setup_header_text),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (isStandalone) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "By continuing you confirm that you have purchased the Keyguard or have had an active license.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(DisabledEmphasisAlpha),
        )
    }
}

fun keyguardSpan() =
    buildAnnotatedString {
        withStyle(
            style = SpanStyle(
                fontWeight = FontWeight.Bold,
            ),
        ) {
            append("Key")
        }
        append("guard")
    }
