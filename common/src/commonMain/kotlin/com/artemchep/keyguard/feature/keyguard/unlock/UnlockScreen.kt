package com.artemchep.keyguard.feature.keyguard.unlock

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.auth.common.autofill
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.OtherScaffold
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.skeleton.SkeletonButton
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

val unlockScreenTitlePadding = 48.dp
val unlockScreenActionPadding = 8.dp

@Composable
fun UnlockScreen(
    /**
     * Unlocks a vault with the key derived from a
     * given password.
     */
    unlockVaultByMasterPassword: VaultState.Unlock.WithPassword,
    unlockVaultByBiometric: VaultState.Unlock.WithBiometric?,
    lockInfo: VaultState.Unlock.LockInfo?,
) {
    val loadableState = unlockScreenState(
        clearData = localDI().direct.instance(),
        unlockVaultByMasterPassword = unlockVaultByMasterPassword,
        unlockVaultByBiometric = unlockVaultByBiometric,
        lockInfo = lockInfo,
    )
    loadableState.fold(
        ifLoading = {
            UnlockScreenSkeleton()
        },
        ifOk = { state ->
            UnlockScreen(state)
        },
    )
}

@Composable
private fun UnlockScreenSkeleton() {
    OtherScaffold {
        UnlockScreenContainer(
            top = {
                UnlockScreenTheVaultIsLockedTitle()
            },
            center = {
                SkeletonTextField(
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            },
            bottom = {
                SkeletonButton(
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun UnlockScreen(
    unlockState: UnlockState,
) {
    val focusRequester = remember {
        FocusRequester2()
    }

    val updatedHasBiometric by rememberUpdatedState(unlockState.biometric != null)
    LaunchedEffect(focusRequester) {
        delay(200L)
        // If we focus a password field and request the biometrics,
        // then the keyboard pops-up after we have successfully used
        // the biometrics to unlock. Looks super junky.
        if (!updatedHasBiometric) {
            focusRequester.requestFocus()
        }
    }

    BiometricPromptEffect(unlockState.sideEffects.showBiometricPromptFlow)
    OtherScaffold(
        actions = {
            OptionsButton(actions = unlockState.actions)
        },
    ) {
        UnlockScreenContainer(
            top = {
                UnlockScreenTheVaultIsLockedTitle()
                ExpandedIfNotEmpty(
                    valueOrNull = unlockState.lockReason,
                ) { lockReason ->
                    Text(
                        modifier = Modifier
                            .padding(top = 16.dp),
                        textAlign = TextAlign.Center,
                        text = lockReason,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            center = {
                val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
                    if (unlockState.unlockVaultByMasterPassword != null) {
                        // lambda
                        {
                            unlockState.unlockVaultByMasterPassword.invoke()
                        }
                    } else {
                        null
                    }
                PasswordFlatTextField(
                    modifier = Modifier,
                    fieldModifier = Modifier
                        .focusRequester2(focusRequester)
                        .autofill(
                            value = unlockState.password.state.value,
                            autofillTypes = listOf(
                                AutofillType.Password,
                            ),
                            onFill = unlockState.password.onChange,
                        ),
                    testTag = "field:password",
                    value = unlockState.password,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = keyboardOnGo,
                    ),
                )
            },
            bottom = {
                val onUnlockButtonClick by rememberUpdatedState(unlockState.unlockVaultByMasterPassword)
                Button(
                    modifier = Modifier
                        .testTag("btn:go")
                        .fillMaxWidth(),
                    enabled = unlockState.unlockVaultByMasterPassword != null,
                    onClick = {
                        onUnlockButtonClick?.invoke()
                    },
                ) {
                    Crossfade(
                        modifier = Modifier
                            .size(24.dp),
                        targetState = unlockState.isLoading,
                    ) { isLoading ->
                        if (isLoading) {
                            KeyguardLoadingIndicator()
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.LockOpen,
                                contentDescription = null,
                            )
                        }
                    }
                    Spacer(
                        modifier = Modifier
                            .width(Dimens.buttonIconPadding),
                    )
                    Text(
                        text = stringResource(Res.string.unlock_button_unlock),
                    )
                }
                val onBiometricButtonClick by rememberUpdatedState(unlockState.biometric?.onClick)
                ExpandedIfNotEmpty(
                    valueOrNull = unlockState.biometric,
                ) { b ->
                    ElevatedButton(
                        modifier = Modifier
                            .padding(top = 32.dp),
                        enabled = b.onClick != null,
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
            },
        )
    }
}

@Composable
private fun UnlockScreenTheVaultIsLockedTitle() {
    Text(
        textAlign = TextAlign.Center,
        text = stringResource(Res.string.unlock_header_text),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
inline fun ColumnScope.UnlockScreenContainer(
    top: @Composable () -> Unit,
    center: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    top()
    Spacer(Modifier.height(unlockScreenTitlePadding))
    center()
    Spacer(Modifier.height(unlockScreenActionPadding))
    bottom()
}
