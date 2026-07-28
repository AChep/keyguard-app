package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ApplicationScope
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequest
import com.artemchep.keyguard.feature.agent.AgentRequestUiState
import com.artemchep.keyguard.feature.gpgagent.GpgAgentApprovalContent
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_agent
import com.artemchep.keyguard.res.gpg_client_request
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ApplicationScope.GpgRequestWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    gpgAgentRequestUiState: Loadable<AgentRequestUiState<GpgAgentRequest>>,
) {
    AgentRequestWindow(
        processLifecycleProvider = processLifecycleProvider,
        requestUiState = gpgAgentRequestUiState,
        title = stringResource(Res.string.gpg_agent),
        authReason = TextHolder.Res(Res.string.gpg_client_request),
        focusTag = "GpgRequestWindow",
        requestContent = { request, onHandled ->
            when (request) {
                is GpgAgentApprovalRequest -> GpgAgentApprovalContent(
                    request = request,
                    onDismiss = onHandled,
                )
            }
        },
    )
}
