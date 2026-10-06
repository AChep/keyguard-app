package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.changepassword.ChangePasswordState
import com.artemchep.keyguard.feature.changepassword.changePasswordStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.auth.AuthPromptHost
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.completeOnPop
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Biometric-unlock users confirm through the producer's `sideEffects.showBiometricPromptFlow`: without
 * collecting it, the prompt never appears and the change hangs.
 */
internal class ChangePasswordController(
    private val ctx: CoreContext,
    private val authPromptHost: AuthPromptHost,
) {
    fun makeChangePasswordSession(): ChangePasswordSession = ChangePasswordSession { publish, complete ->
        val getBiometricRequireConfirmation = ctx.koin.get<GetBiometricRequireConfirmation>()
        val windowCoroutineScope = ctx.koin.get<WindowCoroutineScope>()
        val leContext = ctx.koin.get<LeContext>()
        ctx.launchObserver {
            // The biometric prompt side-effect flow is the same EventFlow instance for the whole producer
            // lifetime; collect it once and route each prompt through the shared native prompt host. The host
            // runs Touch ID / Face ID, then invokes the prompt's onComplete on the main thread (re-encrypt the
            // biometric key, change the password), or delivers the error on cancel / failure.
            var promptCollectorStarted = false
            ctx.koin.newHeadlessStateFlowScope("change_password", this, completeOnPop(ctx.scope, complete))
                .changePasswordStateProducer(
                    unlockUseCase = ctx.unlockUseCase,
                    getBiometricRequireConfirmation = getBiometricRequireConfirmation,
                    windowCoroutineScope = windowCoroutineScope,
                )
                .throttleLatest()
                .map { loadable ->
                    val state = loadable.getOrNull()
                    if (state != null && !promptCollectorStarted) {
                        promptCollectorStarted = true
                        val promptFlow = state.sideEffects.showBiometricPromptFlow
                        val reason = textResource(
                            Res.string.changepassword_biometric_auth_confirm_title,
                            leContext,
                        )
                        launch {
                            promptFlow.collect { prompt ->
                                authPromptHost.handleBiometricPrompt(prompt, reason = reason)
                            }
                        }
                    }
                    state to snapshot(state)
                }
                .collectOnMain { (state, snapshot) ->
                    publish(
                        snapshot,
                        ChangePasswordActions(
                            setCurrentPassword = state?.password?.current?.onChange,
                            setNewPassword = state?.password?.new?.onChange,
                            setBiometric = state?.biometric?.onChange,
                            submit = state?.onConfirm,
                        ),
                    )
                }
        }
    }

    private fun snapshot(state: ChangePasswordState?): ChangePasswordSnapshot = if (state == null) {
        ChangePasswordSnapshot.empty
    } else {
        ChangePasswordSnapshot(
            loaded = true,
            currentPassword = state.password.current.text,
            currentError = state.password.current.error,
            newPassword = state.password.new.text,
            newError = state.password.new.error,
            biometricVisible = state.biometric != null,
            biometricChecked = state.biometric?.checked == true,
            canConfirm = state.onConfirm != null,
            isLoading = state.isLoading,
        )
    }

}
