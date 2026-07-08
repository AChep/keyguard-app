package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ApplicationScope
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentGetListRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequest
import com.artemchep.keyguard.feature.agent.AgentRequestUiState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.sshagent.SshAgentApprovalContent
import com.artemchep.keyguard.feature.sshagent.SshAgentGetListContent
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ssh_agent
import com.artemchep.keyguard.res.ssh_client_request
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ApplicationScope.SshRequestWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    sshAgentRequestUiState: Loadable<AgentRequestUiState<SshAgentRequest>>,
) {
    AgentRequestWindow(
        processLifecycleProvider = processLifecycleProvider,
        requestUiState = sshAgentRequestUiState,
        title = stringResource(Res.string.ssh_agent),
        authReason = TextHolder.Res(Res.string.ssh_client_request),
        focusTag = "SshRequestWindow",
        vaultStateEffect = { vaultState, request, onDismiss ->
            LaunchedEffect(vaultState, request) {
                val getListRequest = request as? SshAgentGetListRequest
                    ?: return@LaunchedEffect
                if (vaultState is VaultState.Main && getListRequest.deferred.complete(true)) {
                    onDismiss()
                }
            }
        },
        requestContent = { request, onHandled ->
            when (request) {
                is SshAgentApprovalRequest -> SshAgentApprovalContent(
                    request = request,
                    onDismiss = onHandled,
                )

                is SshAgentGetListRequest -> SshAgentGetListContent(
                    request = request,
                    onDismiss = onHandled,
                )
            }
        },
    )
}
