package com.artemchep.keyguard.feature.changepassword

import arrow.core.identity
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.finally
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

private const val KEY_BIOMETRIC_ENABLED = "biometric.enabled"

/**
 * Changes the app password or the key derivation of the vault, see
 * [VaultState.Main.ChangePassword.WithPassword.getCreateIo] for the arguments.
 */
internal typealias ChangePasswordAction = (
    currentPassword: String,
    change: VaultState.Main.ChangePassword.Change,
) -> Unit

internal class ChangePasswordActions(
    val change: ChangePasswordAction,
    /**
     * Asks the user to confirm their identity, then changes it and
     * re-enrolls the biometric unlock; `null` if the biometric unlock
     * is not available.
     */
    val changeWithBiometric: ChangePasswordAction?,
)

/**
 * Creates the actions that change the app password or the key derivation
 * of the [state]. A change runs one at a time, see [launchChangePassword].
 *
 * @param successMessage the toast to show once the change completes.
 * @param biometricPromptTitle the title of the biometric prompt.
 */
internal fun RememberStateFlowScope.createChangePasswordActions(
    state: VaultState.Main,
    requireConfirmation: Boolean,
    biometricPromptSink: EventFlow<BiometricAuthPrompt>,
    windowCoroutineScope: WindowCoroutineScope,
    loadingSink: MutableStateFlow<Boolean>,
    successMessage: StringResource = Res.string.changepassword_password_changed_successfully,
    biometricPromptTitle: StringResource = Res.string.changepassword_biometric_auth_confirm_title,
) = ChangePasswordActions(
    change = { currentPassword, change ->
        launchChangePassword(
            io = state.changePassword.withMasterPassword
                .getCreateIo(currentPassword, change),
            windowCoroutineScope = windowCoroutineScope,
            loadingSink = loadingSink,
            successMessage = successMessage,
        )
    },
    changeWithBiometric = if (state.changePassword.withMasterPasswordAndBiometric != null) {
        lambda@{ currentPassword, change ->
            if (loadingSink.value) return@lambda
            screenScope.launch {
                val cipher = state.changePassword.withMasterPasswordAndBiometric.getCipher()
                    .fold(
                        ifLeft = { e ->
                            message(e)
                            return@launch
                        },
                        ifRight = ::identity,
                    )

                val prompt = BiometricAuthPrompt(
                    title = TextHolder.Res(biometricPromptTitle),
                    cipher = cipher,
                    requireConfirmation = requireConfirmation,
                    onComplete = { result ->
                        result.fold(
                            ifLeft = { e ->
                                message(e)
                            },
                            ifRight = {
                                launchChangePassword(
                                    io = state
                                        .changePassword.withMasterPasswordAndBiometric
                                        .getCreateIo(currentPassword, change),
                                    windowCoroutineScope = windowCoroutineScope,
                                    loadingSink = loadingSink,
                                    successMessage = successMessage,
                                )
                            },
                        )
                    },
                )
                biometricPromptSink.emit(prompt)
            }
        }
    } else {
        null
    },
)

/**
 * The biometric re-enroll checkbox, shown while the vault offers
 * the biometric unlock. Checked by default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun RememberStateFlowScope.changePasswordBiometricFlow(
    actionsFlow: Flow<ChangePasswordActions?>,
): Flow<ChangePasswordState.Biometric?> {
    val biometricEnabledSink = mutablePersistedFlow(KEY_BIOMETRIC_ENABLED) {
        true
    }
    return actionsFlow
        .map { it?.changeWithBiometric != null }
        .distinctUntilChanged()
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
}

/**
 * Runs the password change [io], unless one runs already: a second one is ignored. Shows
 * the [loadingSink] meanwhile. Once the password changes, closes the screen and tells what
 * the change did not carry over.
 */
private fun RememberStateFlowScope.launchChangePassword(
    io: IO<VaultState.Main.ChangePassword.Report>,
    windowCoroutineScope: WindowCoroutineScope,
    loadingSink: MutableStateFlow<Boolean>,
    successMessage: StringResource,
) {
    if (!loadingSink.compareAndSet(expect = false, update = true)) {
        return
    }
    io
        .effectTap { report ->
            val msg = ToastMessage(
                title = translate(successMessage),
                type = ToastMessage.Type.SUCCESS,
            )
            message(msg)
            val notResealed = report.authoritiesNotResealed
            if (notResealed > 0) {
                val warning = ToastMessage(
                    title = translate(
                        Res.plurals.changepassword_authority_not_resealed_plural,
                        notResealed,
                        notResealed,
                    ),
                    type = ToastMessage.Type.ERROR,
                )
                message(warning)
            }
            // Pop the screen
            navigatePopSelf()
        }
        .finally {
            loadingSink.value = false
        }
        .launchIn(windowCoroutineScope)
}
