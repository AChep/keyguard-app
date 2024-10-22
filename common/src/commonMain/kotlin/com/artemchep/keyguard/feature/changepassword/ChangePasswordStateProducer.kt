package com.artemchep.keyguard.feature.changepassword

import androidx.compose.runtime.Composable
import arrow.core.identity
import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private const val KEY_PASSWORD_OLD = "password.old"
private const val KEY_PASSWORD_NEW = "password.new"

private const val KEY_BIOMETRIC_ENABLED = "biometric.enabled"

@Composable
fun changePasswordState(): Loadable<ChangePasswordState> = with(localDI().direct) {
    changePasswordState(
        unlockUseCase = instance(),
        getBiometricRequireConfirmation = instance(),
        windowCoroutineScope = instance(),
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
    val biometricPromptSink = EventFlow<BiometricAuthPrompt>()

    val passwordOldSink = mutablePersistedFlow(KEY_PASSWORD_OLD) { "" }
    val passwordOldState = mutableComposeState(passwordOldSink)

    val passwordNewSink = mutablePersistedFlow(KEY_PASSWORD_NEW) { "" }
    val passwordNewState = mutableComposeState(passwordNewSink)

    val fn = unlockUseCase()
        .map { vaultState ->
            when (vaultState) {
                is VaultState.Main -> {
                    val requireConfirmation = getBiometricRequireConfirmation()
                        .first()
                    ah(
                        state = vaultState,
                        requireConfirmation = requireConfirmation,
                        biometricPromptSink = biometricPromptSink,
                        windowCoroutineScope = windowCoroutineScope,
                    )
                }

                else -> null
            }
        }

    val biometricAvailableFlow = fn
        .map { it?.changePasswordWithBiometric != null }
        .distinctUntilChanged()
    val biometricWasEnabled = true
    val biometricEnabledSink = mutablePersistedFlow(KEY_BIOMETRIC_ENABLED) {
        biometricWasEnabled
    }

    val validatedPasswordFlow = passwordOldSink
        .validatedPassword(this)
    val validatedPasswordNewFlow = passwordNewSink
        .validatedPassword(this)

    val passwordFlow = combine(
        validatedPasswordFlow,
        validatedPasswordNewFlow,
    ) {
            validatedPassword,
            validatedPasswordNew,
        ->
        ChangePasswordState.Password(
            current = TextFieldModel2.of(
                state = passwordOldState,
                validated = validatedPassword,
            ),
            new = TextFieldModel2.of(
                state = passwordNewState,
                validated = validatedPasswordNew,
            ),
        )
    }

    val biometricFlow = biometricAvailableFlow
        .flatMapLatest { available ->
            if (available) {
                biometricEnabledSink
                    .map { enabled ->
                        ChangePasswordState.Biometric(
                            checked = enabled,
                            onChange = biometricEnabledSink::value::set,
                        )
                    }
            } else {
                flowOf(null)
            }
        }

    combine(
        passwordFlow,
        biometricFlow,
        fn,
    ) { password, biometric, f ->
        val isValid = password.current.error == null &&
                password.current.text.isNotEmpty() &&
                password.new.error == null &&
                password.new.text.isNotEmpty()
        val onConfirm = if (isValid && f != null) {
            if (biometric?.checked == true) {
                f.changePasswordWithBiometric
            } else {
                f.changePassword
            }
                ?.partially1(password.current.text)
                ?.partially1(password.new.text)
        } else {
            null
        }
        val state = ChangePasswordState(
            sideEffects = ChangePasswordState.SideEffects(
                showBiometricPromptFlow = biometricPromptSink,
            ),
            password = password,
            biometric = biometric,
            onConfirm = onConfirm,
        )
        Loadable.Ok(state)
    }
}

private fun RememberStateFlowScope.ah(
    state: VaultState.Main,
    requireConfirmation: Boolean,
    biometricPromptSink: EventFlow<BiometricAuthPrompt>,
    windowCoroutineScope: WindowCoroutineScope,
) = Fn(
    changePassword = { currentPassword, newPassword ->
        val io = state.changePassword.withMasterPassword
            .getCreateIo(currentPassword, newPassword)
            .effectTap {
                val msg = ToastMessage(
                    title = translate(Res.string.changepassword_password_changed_successfully),
                    type = ToastMessage.Type.SUCCESS,
                )
                message(msg)
                // Pop the screen
                navigatePopSelf()
            }
        io.launchIn(windowCoroutineScope)
    },
    changePasswordWithBiometric = if (state.changePassword.withMasterPasswordAndBiometric != null) {
        lambda@{ currentPassword, newPassword ->
            val cipher = state.changePassword.withMasterPasswordAndBiometric.getCipher()
                .fold(
                    ifLeft = { e ->
                        message(e)
                        return@lambda
                    },
                    ifRight = ::identity,
                )
            val prompt = BiometricAuthPrompt(
                title = TextHolder.Res(Res.string.changepassword_biometric_auth_confirm_title),
                cipher = cipher,
                requireConfirmation = requireConfirmation,
                onComplete = { result ->
                    result.fold(
                        ifLeft = { e ->
                            message(e)
                        },
                        ifRight = {
                            val io = state
                                .changePassword.withMasterPasswordAndBiometric
                                .getCreateIo(currentPassword, newPassword)
                                .effectTap {
                                    val msg = ToastMessage(
                                        title = translate(Res.string.changepassword_password_changed_successfully),
                                        type = ToastMessage.Type.SUCCESS,
                                    )
                                    message(msg)
                                    // Pop the screen
                                    navigatePopSelf()
                                }
                            io.launchIn(windowCoroutineScope)
                        },
                    )
                },
            )
            biometricPromptSink.emit(prompt)
        }
    } else {
        null
    },
)

private data class Fn(
    val changePassword: (String, String) -> Unit,
    val changePasswordWithBiometric: ((String, String) -> Unit)?,
)
