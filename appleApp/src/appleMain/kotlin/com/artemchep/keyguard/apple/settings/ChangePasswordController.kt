package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.auth.AuthPromptHost
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.changepassword.ChangePasswordState
import com.artemchep.keyguard.feature.changepassword.changePasswordStateProducer
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/** Biometric-unlock users confirm the change in a prompt, see [BiometricConfirmedFormController]. */
internal class ChangePasswordController(
    ctx: CoreContext,
    authPromptHost: AuthPromptHost,
) : BiometricConfirmedFormController(ctx, authPromptHost) {
    fun makeChangePasswordSession(): ChangePasswordSession = ChangePasswordSession(
        subscribe(
            name = "change_password",
            promptReason = Res.string.changepassword_biometric_auth_confirm_title,
            sideEffects = ChangePasswordState::sideEffects,
            produce = {
                changePasswordStateProducer(
                    unlockUseCase = ctx.unlockUseCase,
                    getBiometricRequireConfirmation = ctx.koin.get<GetBiometricRequireConfirmation>(),
                    windowCoroutineScope = ctx.koin.get<WindowCoroutineScope>(),
                )
            },
            present = { state -> snapshot(state) to actions(state) },
        ),
    )

    private fun actions(state: ChangePasswordState?) = ChangePasswordActions(
        setCurrentPassword = state?.password?.current?.onChange,
        setNewPassword = state?.password?.new?.onChange,
        setBiometric = state?.biometric?.onChange,
        submit = state?.onConfirm,
    )

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
