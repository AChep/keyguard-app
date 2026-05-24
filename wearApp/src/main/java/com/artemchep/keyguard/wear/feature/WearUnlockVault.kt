package com.artemchep.keyguard.wear.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.TextField
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.ClearData
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.keyguard.setup.setupScreenState
import com.artemchep.keyguard.feature.keyguard.unlock.unlockScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.setup_button_create_vault
import com.artemchep.keyguard.res.unlock_button_unlock
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.changepassword.ChangePasswordState
import com.artemchep.keyguard.feature.keyguard.LocalAuthScreen
import com.artemchep.keyguard.feature.keyguard.setup.SetupState
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockScreenTheVaultIsLockedTitle
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.TextHolder.Value
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.yubikey.YubiKeyPromptEffect
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.DefaultEdgeButton
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import com.artemchep.keyguard.wear.ui.WearScaffoldLoader
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

private const val AppTitle = "Keyguard"

@Composable
fun WearUnlockVaultScreen(
    state: VaultState.Unlock,
) {
    val clearData by rememberInstance<ClearData>()
    val loadableState = unlockScreenState(
        clearData = clearData,
        unlockVaultByMasterPassword = state.unlockWithMasterPassword,
        unlockVaultByBiometric = state.unlockWithBiometric,
        unlockVaultByYubiKey = state.unlockWithYubiKey,
        lockInfo = state.lockInfo,
    )

    // Consume side effects
    loadableState.fold(
        ifLoading = {},
        ifOk = { state ->
            BiometricPromptEffect(state.sideEffects.showBiometricPromptFlow)
            YubiKeyPromptEffect(state.sideEffects.showYubiKeyPromptFlow)
        },
    )

    WearScaffoldScreen(
        title = AppTitle,
        floatingActionState = rememberUnlockUnlockFabState(loadableState),
        floatingActionButton = {
            DefaultEdgeButton(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.LockOpen,
                        contentDescription = null,
                    )
                },
            ) {
                Text(
                    text = stringResource(Res.string.unlock_button_unlock),
                )
            }
        },
        overlay = {
            WearScaffoldLoader(
                modifier = Modifier,
                visible = loadableState
                    .fold(
                        ifLoading = { true },
                        ifOk = { state ->
                            state.isLoading
                        },
                    ),
            )
        },
    ) { transformationSpec ->
        when (val state = loadableState) {
            is Loadable.Loading -> {
                // Do nothing
            }

            is Loadable.Ok -> {
                WearUnlockVaultContent(
                    state = state.value,
                    transformationSpec = transformationSpec,
                )
            }
        }
    }
}

@Composable
fun rememberUnlockUnlockFabState(
    state: Loadable<UnlockState>,
): State<FabState?> {
    val fabOnClick = state.fold(
        ifLoading = { null },
        ifOk = {
            it.unlockVaultByMasterPassword
        },
    )
    val fabState = FabState(
        onClick = fabOnClick,
        model = null,
    )
    return rememberUpdatedState(newValue = fabState)
}

private fun TransformingLazyColumnScope.WearUnlockVaultContent(
    state: UnlockState,
    transformationSpec: TransformationSpec,
) {
    item("top") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec)
                .surfaceTransformation(surfaceTransformation),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ProxyMaterial3Styles {
                UnlockScreenTheVaultIsLockedTitle()
            }
            val infoOrNull = LocalAuthScreen.current.reason
                ?: state.lockReason
                    ?.let(TextHolder::Value)
            ExpandedIfNotEmpty(
                valueOrNull = infoOrNull,
            ) { info ->
                Text(
                    modifier = Modifier
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    text = textResource(info),
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
        }
    }
    item("center") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val keyboardOnGo: (KeyboardActionScope.() -> Unit)? = run {
            val unlockVaultByMasterPassword = state.unlockVaultByMasterPassword
            if (unlockVaultByMasterPassword != null) {
                // lambda
                {
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                    unlockVaultByMasterPassword.invoke()
                }
            } else {
                null
            }
        }
        PasswordFlatTextField(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec)
                .surfaceTransformation(surfaceTransformation),
            fieldModifier = Modifier,
            testTag = "field:password",
            value = state.password,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Go,
                keyboardType = KeyboardType.NumberPassword,
            ),
            keyboardActions = KeyboardActions(
                onGo = keyboardOnGo,
            ),
        )
    }
}
