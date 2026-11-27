package com.artemchep.keyguard.feature.changepassword

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.modelFlow
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.compose.currentKoinScope

private const val KEY_PASSWORD_OLD = "password.old"
private const val KEY_PASSWORD_NEW = "password.new"

@Composable
fun changePasswordState(): Loadable<ChangePasswordState> = with(currentKoinScope()) {
    changePasswordState(
        unlockUseCase = get(),
        getBiometricRequireConfirmation = get(),
        // Rotation replaces the vault scope before recording success and closing this screen.
        windowCoroutineScope = getKoin().get<WindowCoroutineScope>(),
    )
}

@Composable
fun changePasswordState(
    unlockUseCase: UnlockUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    windowCoroutineScope: WindowCoroutineScope,
): Loadable<ChangePasswordState> = produceScreenState(
    key = "change_password",
    initial = Loadable.Loading,
    args = arrayOf(
        unlockUseCase,
    ),
) {
    changePasswordStateProducer(
        unlockUseCase = unlockUseCase,
        getBiometricRequireConfirmation = getBiometricRequireConfirmation,
        windowCoroutineScope = windowCoroutineScope,
    )
}

suspend fun RememberStateFlowScope.changePasswordStateProducer(
    unlockUseCase: UnlockUseCase,
    getBiometricRequireConfirmation: GetBiometricRequireConfirmation,
    windowCoroutineScope: WindowCoroutineScope,
): Flow<Loadable<ChangePasswordState>> {
    val biometricPromptSink = EventFlow<BiometricAuthPrompt>()

    val passwordOldHandle = textFieldHandle(KEY_PASSWORD_OLD)
    val passwordNewHandle = textFieldHandle(KEY_PASSWORD_NEW)

    // A password change runs one at a time: a second one would race the
    // re-key of the vault with the first one.
    val loadingSink = MutableStateFlow(false)

    val fn = unlockUseCase()
        .map { vaultState ->
            when (vaultState) {
                is VaultState.Main -> {
                    val requireConfirmation = getBiometricRequireConfirmation()
                        .first()
                    createChangePasswordActions(
                        state = vaultState,
                        requireConfirmation = requireConfirmation,
                        biometricPromptSink = biometricPromptSink,
                        windowCoroutineScope = windowCoroutineScope,
                        loadingSink = loadingSink,
                    )
                }

                else -> null
            }
        }

    val currentFieldFlow = passwordOldHandle.modelFlow { password ->
        validatedPassword(password)
    }
    val newFieldFlow = passwordNewHandle.modelFlow { password ->
        validatedPassword(password)
    }

    val passwordFlow = combine(
        currentFieldFlow,
        newFieldFlow,
    ) { current, new ->
        ChangePasswordState.Password(
            current = current,
            new = new,
        )
    }

    val biometricFlow = changePasswordBiometricFlow(
        actionsFlow = fn,
    )

    return combine(
        passwordFlow,
        biometricFlow,
        fn,
        loadingSink,
    ) { password, biometric, f, isLoading ->
        val isValid = password.current.error == null &&
                password.current.text.isNotEmpty() &&
                password.new.error == null &&
                password.new.text.isNotEmpty()
        val onConfirm = if (isValid && f != null) {
            if (biometric?.checked == true) {
                f.changeWithBiometric
            } else {
                f.change
            }
                ?.partially1(password.current.text)
                ?.partially1(VaultState.Main.ChangePassword.Change.Password(password.new.text))
        } else {
            null
        }
        val state = ChangePasswordState(
            sideEffects = ChangePasswordState.SideEffects(
                showBiometricPromptFlow = biometricPromptSink,
            ),
            password = password,
            biometric = biometric,
            isLoading = isLoading,
            onConfirm = onConfirm,
        )
        Loadable.Ok(state)
    }
}
