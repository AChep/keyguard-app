package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.changepassword.ChangePasswordState
import com.artemchep.keyguard.feature.changepassword.changePasswordStateProducer

internal class ChangePasswordController(
    private val ctx: CoreContext,
) {
    fun makeChangePasswordSession(): ChangePasswordSession = ChangePasswordSession(
        ctx.subscribeHeadlessForm(
            name = "change_password",
            produce = {
                changePasswordStateProducer(
                    unlockUseCase = ctx.unlockUseCase,
                    windowCoroutineScope = ctx.koin.get<WindowCoroutineScope>(),
                )
            },
            present = { state -> snapshot(state) to actions(state) },
        ),
    )

    private fun actions(state: ChangePasswordState?) = ChangePasswordActions(
        setCurrentPassword = state?.password?.current?.onChange,
        setNewPassword = state?.password?.new?.onChange,
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
            canConfirm = state.onConfirm != null,
            isLoading = state.isLoading,
        )
    }
}
