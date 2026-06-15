package com.artemchep.keyguard.feature.keyguard.unlock

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.dialog.DialogContent
import com.artemchep.keyguard.feature.keyguard.AuthScreen
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.yubikey.YubiKeyPromptEffect
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LocalWindowRev
import com.artemchep.keyguard.platform.Platform
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
import com.artemchep.keyguard.ui.icons.KeyguardYubiKey
import com.artemchep.keyguard.ui.rememberCountdownSeconds
import com.artemchep.keyguard.ui.skeleton.SkeletonButton
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.time.Duration.Companion.milliseconds

val unlockScreenTitlePadding = 24.dp
val unlockScreenActionPadding = 8.dp

@Composable
fun UnlockScreen(
    /**
     * Unlocks a vault with the key derived from a
     * given password.
     */
    unlockVaultByMasterPassword: VaultState.Unlock.WithPassword,
    unlockVaultByBiometric: VaultState.Unlock.WithBiometric?,
    unlockVaultByYubiKey: VaultState.Unlock.WithYubiKey?,
    lockInfo: VaultState.Unlock.LockInfo?,
) {
    val loadableState = unlockScreenState(
        clearData = localDI().direct.instance(),
        unlockVaultByMasterPassword = unlockVaultByMasterPassword,
        unlockVaultByBiometric = unlockVaultByBiometric,
        unlockVaultByYubiKey = unlockVaultByYubiKey,
        lockInfo = lockInfo,
    )
    when (LocalAuthScreen.current.style) {
        AuthScreen.Style.FULL_SCREEN -> {
            loadableState.fold(
                ifLoading = {
                    UnlockScreenSkeleton()
                },
                ifOk = { state ->
                    UnlockScreen(state)
                },
            )
        }

        AuthScreen.Style.DIALOG -> {
            loadableState.fold(
                ifLoading = {
                    UnlockDialogSkeleton()
                },
                ifOk = { state ->
                    UnlockDialog(state)
                },
            )
        }
    }
}

@Composable
private fun UnlockDialogSkeleton() {
    DialogContent(
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp),
            ) {
                Image(
                    modifier = Modifier
                        .fillMaxSize(),
                    painter = painterResource(Res.drawable.ic_keyguard),
                    contentDescription = null,
                )
            }
        },
        title = null,
        content = {
            Column {
                SkeletonTextField(
                    modifier = Modifier
                        .padding(horizontal = Dimens.fieldHorizontalPadding)
                        .fillMaxWidth(),
                )
            }
        },
        fill = true,
        actions = {
            SkeletonButton(
                modifier = Modifier
                    .weight(1f),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UnlockDialog(
    unlockState: UnlockState,
) {
    val focusRequester = rememberFocusRequesterAndAutoRequest(unlockState)

    BiometricPromptEffect(unlockState.sideEffects.showBiometricPromptFlow)
    YubiKeyPromptEffect(unlockState.sideEffects.showYubiKeyPromptFlow)

    val infoOrNull = LocalAuthScreen.current.reason
        ?: unlockState.lockReason
            ?.let(TextHolder::Value)
    DialogContent(
        icon = {
            Box(
                modifier = Modifier
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .fillMaxSize(),
            ) {
                Image(
                    modifier = Modifier
                        .fillMaxSize(),
                    painter = painterResource(Res.drawable.ic_keyguard),
                    contentDescription = null,
                )
            }
        },
        title = if (infoOrNull != null) {
            // composable
            {
                Text(
                    text = textResource(infoOrNull),
                )
            }
        } else {
            null
        },
        content = {
            Column {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    text = stringResource(Res.string.unlock_header_text),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )

                UnlockPasswordField(
                    modifier = Modifier
                        .padding(horizontal = Dimens.fieldHorizontalPadding)
                        .fillMaxWidth(),
                    unlockState = unlockState,
                    focusRequester = focusRequester,
                )

                ExpandedHardwareUnlockIfExists(
                    unlockState = unlockState,
                )
            }
        },
        fill = true,
        actions = {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val updatedOnCancel by rememberUpdatedState(LocalAuthScreen.current.onCancel)
                if (updatedOnCancel != null) {
                    TextButton(
                        modifier = Modifier
                            .weight(1f, fill = true),
                        onClick = {
                            updatedOnCancel?.invoke()
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(
                            text = stringResource(Res.string.cancel),
                            maxLines = 1,
                        )
                    }
                }
                UnlockCtaButton(
                    modifier = Modifier
                        .weight(1f, fill = true),
                    unlockState = unlockState,
                    showIcon = updatedOnCancel == null, // save space
                )
            }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UnlockScreen(
    unlockState: UnlockState,
) {
    val focusRequester = rememberFocusRequesterAndAutoRequest(unlockState)

    BiometricPromptEffect(unlockState.sideEffects.showBiometricPromptFlow)
    YubiKeyPromptEffect(unlockState.sideEffects.showYubiKeyPromptFlow)
    OtherScaffold(
        actions = {
            OptionsButton(actions = unlockState.actions)
        },
    ) {
        UnlockScreenContainer(
            top = {
                UnlockScreenTheVaultIsLockedTitle()
                val infoOrNull = LocalAuthScreen.current.reason
                    ?: unlockState.lockReason
                        ?.let(TextHolder::Value)
                ExpandedIfNotEmpty(
                    valueOrNull = infoOrNull,
                ) { info ->
                    Text(
                        modifier = Modifier
                            .padding(top = 16.dp),
                        textAlign = TextAlign.Center,
                        text = textResource(info),
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            center = {
                UnlockPasswordField(
                    modifier = Modifier,
                    unlockState = unlockState,
                    focusRequester = focusRequester,
                )
            },
            bottom = {
                UnlockCtaButton(
                    modifier = Modifier
                        .fillMaxWidth(),
                    unlockState = unlockState,
                )
                ExpandedHardwareUnlockIfExists(
                    unlockState = unlockState,
                )
            },
        )
    }
}

@Composable
private fun rememberFocusRequesterAndAutoRequest(
    unlockState: UnlockState,
): FocusRequester2 {
    val windowRev = LocalWindowRev.current
    val focusRequester = remember {
        FocusRequester2()
    }

    val updatedHasHardwareUnlock by rememberUpdatedState(
        unlockState.biometric != null || unlockState.yubiKey != null,
    )
    LaunchedEffect(focusRequester, windowRev) {
        val delayMs = if (CurrentPlatform is Platform.Mobile) {
            200L
        } else {
            100L
        }
        delay(delayMs.milliseconds)
        // If we focus a password field and request the biometrics,
        // then the keyboard pops-up after we have successfully used
        // the biometrics to unlock. Looks super junky.
        if (!updatedHasHardwareUnlock) {
            focusRequester.requestFocus()
        }
    }

    return focusRequester
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UnlockPasswordField(
    modifier: Modifier = Modifier,
    unlockState: UnlockState,
    focusRequester: FocusRequester2,
) {
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
        modifier = modifier,
        fieldModifier = Modifier
            .focusRequester2(focusRequester)
            .contentType(ContentType.Password),
        testTag = "field:password",
        value = unlockState.password,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = keyboardOnGo,
        ),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UnlockCtaButton(
    modifier: Modifier = Modifier,
    unlockState: UnlockState,
    showIcon: Boolean = true,
) {
    val onUnlockButtonClick by rememberUpdatedState(unlockState.unlockVaultByMasterPassword)
    Button(
        modifier = modifier
            .testTag("btn:go"),
        shapes = ButtonDefaults.shapes(),
        enabled = unlockState.unlockVaultByMasterPassword != null,
        contentPadding = ButtonDefaults.contentPaddingFor(
            buttonHeight = ButtonDefaults.MinHeight,
        ),
        onClick = {
            onUnlockButtonClick?.invoke()
        },
    ) {
        if (showIcon) {
            Crossfade(
                modifier = Modifier
                    .size(ButtonDefaults.IconSize),
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
                    .width(ButtonDefaults.IconSpacing),
            )
        }

        val textStyle = LocalTextStyle.current
        Text(
            modifier = Modifier
                .weight(1f, fill = false),
            text = stringResource(Res.string.unlock_button_unlock),
            maxLines = 1,
            style = textStyle,
        )

        val countdown = rememberCountdownSeconds(LocalAuthScreen.current.expiresAt)
        if (countdown != null) {
            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )
            Text(
                text = "($countdown)",
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                maxLines = 1,
                style = textStyle,
            )
        }
    }
}

/**
 * Expands as a row of additional unlock methods, if those are
 * currently set up and available.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpandedHardwareUnlockIfExists(
    unlockState: UnlockState,
) {
    ExpandedIfNotEmpty(
        valueOrNull = Unit.takeIf {
            unlockState.biometric != null || unlockState.yubiKey != null
        },
    ) {
        val onBiometricButtonClick by rememberUpdatedState(unlockState.biometric?.onClick)
        val onYubiKeyButtonClick by rememberUpdatedState(unlockState.yubiKey?.onClick)
        Row(
            modifier = Modifier
                .padding(top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (unlockState.biometric != null) {
                Button(
                    enabled = unlockState.biometric.onClick != null,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    elevation = null,
                    border = ButtonDefaults.outlinedButtonBorder(),
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
            if (unlockState.yubiKey != null) {
                Button(
                    enabled = unlockState.yubiKey.onClick != null,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    elevation = null,
                    border = ButtonDefaults.outlinedButtonBorder(),
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
}

@Composable
fun UnlockScreenTheVaultIsLockedTitle(
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
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
