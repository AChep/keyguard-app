package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.auth.AuthPromptHost
import com.artemchep.keyguard.apple.auth.MasterKdfHandlers
import com.artemchep.keyguard.apple.auth.toMasterKdfHandlers
import com.artemchep.keyguard.apple.auth.toMasterKdfSnapshot
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.changekdf.ChangeKdfState
import com.artemchep.keyguard.feature.changekdf.changeKdfStateProducer
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/** Like [ChangePasswordController]: biometric-unlock users confirm the change in a prompt. */
internal class ChangeKdfController(
    ctx: CoreContext,
    authPromptHost: AuthPromptHost,
) : BiometricConfirmedFormController(ctx, authPromptHost) {
    fun makeChangeKdfSession(): ChangeKdfSession = ChangeKdfSession(
        subscribe(
            name = "change_kdf",
            promptReason = Res.string.changekdf_biometric_auth_confirm_title,
            sideEffects = ChangeKdfState::sideEffects,
            produce = {
                changeKdfStateProducer(
                    unlockUseCase = ctx.unlockUseCase,
                    getBiometricRequireConfirmation = ctx.koin.get<GetBiometricRequireConfirmation>(),
                    measureMasterKdf = ctx.koin.get<MeasureMasterKdfUseCase>(),
                    windowCoroutineScope = ctx.koin.get<WindowCoroutineScope>(),
                )
            },
            present = { state -> snapshot(state) to actions(state) },
        ),
    )

    private fun actions(state: ChangeKdfState?) = ChangeKdfActions(
        setPassword = state?.password?.onChange,
        kdf = state?.kdf?.toMasterKdfHandlers() ?: MasterKdfHandlers(),
        setBiometric = state?.biometric?.onChange,
        submit = state?.onConfirm,
    )

    private fun snapshot(state: ChangeKdfState?): ChangeKdfSnapshot = if (state == null) {
        ChangeKdfSnapshot.empty
    } else {
        ChangeKdfSnapshot(
            loaded = true,
            password = state.password.text,
            passwordError = state.password.error,
            kdf = state.kdf.toMasterKdfSnapshot(),
            biometricVisible = state.biometric != null,
            biometricChecked = state.biometric?.checked == true,
            canConfirm = state.onConfirm != null,
            isLoading = state.isLoading,
        )
    }
}
