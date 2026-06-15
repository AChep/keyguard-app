package com.artemchep.keyguard.wear.feature.changepassword

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.changepassword.ChangePasswordState
import com.artemchep.keyguard.feature.changepassword.changePasswordState
import com.artemchep.keyguard.feature.changepassword.rememberChangePasswordFabState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.wear.ui.DefaultEdgeButton
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.skeletonItems
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearChangePasswordScreen() {
    val loadableState = changePasswordState()
    loadableState.fold(
        ifLoading = {
            WearChangePasswordScaffoldSkeleton()
        },
        ifOk = { state ->
            WearChangePasswordScaffold(
                state = state,
            )
        },
    )
}

@Composable
fun WearChangePasswordScaffoldSkeleton() {
    WearScaffoldScreen(
        title = stringResource(Res.string.changepassword_header_title),
    ) { transformationSpec ->
        skeletonItems(
            transformationSpec = transformationSpec,
            count = 5,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WearChangePasswordScaffold(
    state: ChangePasswordState,
) {
    BiometricPromptEffect(state.sideEffects.showBiometricPromptFlow)

    WearScaffoldScreen(
        title = stringResource(Res.string.changepassword_header_title),
        floatingActionState = rememberChangePasswordFabState(state),
        floatingActionButton = {
            DefaultEdgeButton(
                icon = {
                    Crossfade(
                        modifier = Modifier
                            .size(16.dp),
                        targetState = state.isLoading,
                    ) { isLoading ->
                        if (isLoading) {
                            KeyguardLoadingIndicator()
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = null,
                            )
                        }
                    }
                },
                text = {
                    Text(
                        text = stringResource(Res.string.changepassword_change_password_button),
                    )
                },
            )
        },
    ) { transformationSpec ->
        WearChangePasswordContent(
            state = state,
            transformationSpec = transformationSpec,
        )
    }
}

private fun TransformingLazyColumnScope.WearChangePasswordContent(
    state: ChangePasswordState,
    transformationSpec: TransformationSpec,
) {
    item("password.current") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        PasswordFlatTextField(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec)
                .surfaceTransformation(surfaceTransformation),
            value = state.password.current,
            label = stringResource(Res.string.current_password),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
            ),
        )
    }
    item("password.new") {
        val surfaceTransformation = SurfaceTransformation(transformationSpec)
        PasswordFlatTextField(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec)
                .surfaceTransformation(surfaceTransformation),
            value = state.password.new,
            label = stringResource(Res.string.new_password),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
            ),
        )
    }
    val biometric = state.biometric
    if (biometric != null) {
        item("biometric") {
            val surfaceTransformation = SurfaceTransformation(transformationSpec)
            SwitchButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                checked = biometric.checked,
                enabled = biometric.onChange != null,
                onCheckedChange = { newValue ->
                    biometric.onChange?.invoke(newValue)
                },
                label = {
                    Text(
                        text = stringResource(Res.string.changepassword_biometric_auth_checkbox),
                    )
                },
                transformation = surfaceTransformation,
            )
        }
    }
    item("disclaimer.local") {
        WearListLabel(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            text = stringResource(Res.string.changepassword_disclaimer_local_note),
            textAlign = TextAlign.Start,
            transformation = SurfaceTransformation(transformationSpec),
        )
    }
    item("disclaimer.abuse") {
        WearListLabel(
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            text = stringResource(Res.string.changepassword_disclaimer_abuse_note),
            textAlign = TextAlign.Start,
            transformation = SurfaceTransformation(transformationSpec),
        )
    }
}
