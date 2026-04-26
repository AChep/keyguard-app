package com.artemchep.keyguard.wear.feature.changepassword

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.changepassword.ChangePasswordContent
import com.artemchep.keyguard.feature.changepassword.ChangePasswordContentSkeleton
import com.artemchep.keyguard.feature.changepassword.ChangePasswordFab
import com.artemchep.keyguard.feature.changepassword.ChangePasswordState
import com.artemchep.keyguard.feature.changepassword.changePasswordState
import com.artemchep.keyguard.feature.changepassword.rememberChangePasswordFabState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.wear.ui.WearScaffoldColumn
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WearChangePasswordScaffoldSkeleton() {
    WearScaffoldColumn(
        title = stringResource(Res.string.changepassword_header_title),
    ) {
        ChangePasswordContentSkeleton()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun WearChangePasswordScaffold(
    state: ChangePasswordState,
) {
    BiometricPromptEffect(state.sideEffects.showBiometricPromptFlow)

    WearScaffoldColumn(
        title = stringResource(Res.string.changepassword_header_title),
        floatingActionState = rememberChangePasswordFabState(state),
        floatingActionButton = {
            ChangePasswordFab(
                state = state,
            )
        },
    ) {
        ChangePasswordContent(
            state = state,
        )
    }
}
