package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res

/**
 * Tells the result of a commit whose screen closed before it finished, as the
 * step that would have shown it is gone with the screen.
 */
internal suspend fun RememberStateFlowScope.showClosedScreenCommitResult(
    step: Step,
) {
    val message = when (step) {
        is Step.Done -> ToastMessage(
            type = ToastMessage.Type.SUCCESS,
            title = translate(Res.string.import_success_title),
            text = translate(
                Res.plurals.import_success_text,
                step.itemCount,
                step.itemCount,
            ),
        )

        is Step.Error -> ToastMessage(
            type = ToastMessage.Type.ERROR,
            title = step.message,
        )

        else -> return
    }
    message(message)
}
