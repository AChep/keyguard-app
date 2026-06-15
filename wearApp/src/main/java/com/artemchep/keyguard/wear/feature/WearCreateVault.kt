package com.artemchep.keyguard.wear.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.feature.keyguard.setup.SetupState
import com.artemchep.keyguard.feature.keyguard.setup.keyguardSpan
import com.artemchep.keyguard.feature.keyguard.setup.setupScreenState
import com.artemchep.keyguard.feature.keyguard.unlock.unlockScreenTitlePadding
import com.artemchep.keyguard.res.setup_button_create_vault
import com.artemchep.keyguard.res.setup_free_text
import com.artemchep.keyguard.res.setup_header_text
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.DefaultEdgeButton
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import com.artemchep.keyguard.wear.ui.WearScaffoldLoader
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance
import kotlin.getValue

private const val AppTitle = "Keyguard"

@Composable
fun WearCreateVaultScreen(
    state: VaultState.Create,
) {
    val loadableState = setupScreenState(
        createVaultWithMasterPassword = state.createWithMasterPassword,
        createVaultWithMasterPasswordAndBiometric = state.createWithMasterPasswordAndBiometric,
    )

    // Consume side effects
    loadableState.fold(
        ifLoading = {},
        ifOk = { state ->
            BiometricPromptEffect(state.sideEffects.showBiometricPromptFlow)
        },
    )

    WearScaffoldScreen(
        title = AppTitle,
        floatingActionState = rememberCreateVaultFabState(loadableState),
        floatingActionButton = {
            DefaultEdgeButton(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                },
            ) {
                Text(
                    text = stringResource(Res.string.setup_button_create_vault),
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
                WearCreateVaultContent(
                    state = state.value,
                    transformationSpec = transformationSpec,
                )
            }
        }
    }
}

@Composable
fun rememberCreateVaultFabState(
    state: Loadable<SetupState>,
): State<FabState?> {
    val fabOnClick = state.fold(
        ifLoading = { null },
        ifOk = {
            it.onCreateVault
        },
    )
    val fabState = FabState(
        onClick = fabOnClick,
        model = null,
    )
    return rememberUpdatedState(newValue = fabState)
}

private fun TransformingLazyColumnScope.WearCreateVaultContent(
    state: SetupState,
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
                SetupScreenCreateVaultTitle()
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
        val keyboardOnGo: (KeyboardActionScope.() -> Unit)? = kotlin.run {
            val unlockVaultByMasterPassword = state.onCreateVault
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

@Composable
private fun ColumnScope.SetupScreenCreateVaultTitle() {
    Text(
        modifier = Modifier
            .fillMaxWidth(),
        text = stringResource(Res.string.setup_header_text),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}
