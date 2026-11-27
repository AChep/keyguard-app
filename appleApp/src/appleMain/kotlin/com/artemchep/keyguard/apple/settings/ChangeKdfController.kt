package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.auth.MasterKdfHandlers
import com.artemchep.keyguard.apple.auth.toMasterKdfHandlers
import com.artemchep.keyguard.apple.auth.toMasterKdfSnapshot
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.changekdf.ChangeKdfState
import com.artemchep.keyguard.feature.changekdf.changeKdfStateProducer

internal class ChangeKdfController(
    private val ctx: CoreContext,
) {
    fun makeChangeKdfSession(): ChangeKdfSession = ChangeKdfSession(
        ctx.subscribeHeadlessForm(
            name = "change_kdf",
            produce = {
                changeKdfStateProducer(
                    unlockUseCase = ctx.unlockUseCase,
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
            canConfirm = state.onConfirm != null,
            isLoading = state.isLoading,
        )
    }
}
