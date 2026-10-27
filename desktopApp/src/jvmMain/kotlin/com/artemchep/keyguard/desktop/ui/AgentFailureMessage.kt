package com.artemchep.keyguard.desktop.ui

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.agent.AgentFailure
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_failed_agent_start
import org.jetbrains.compose.resources.StringResource

/**
 * Tells the user that the agent of [protocol] failed: on the first failure
 * of a streak, and once the supervisor gives up on restarting it.
 */
internal suspend fun showAgentFailure(
    failure: AgentFailure,
    protocol: StringResource,
    translatorScope: TranslatorScope,
    showMessage: ShowMessage,
) {
    if (failure.attempt != 1 && failure.willRetry) return
    val text = getErrorReadableMessage(failure.error, translatorScope)
        .title
    val msg = ToastMessage(
        type = ToastMessage.Type.ERROR,
        title = translatorScope.translate(
            Res.string.error_failed_agent_start,
            translatorScope.translate(protocol),
        ),
        text = text,
    )
    showMessage.copy(msg)
}
