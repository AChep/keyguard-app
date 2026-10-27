package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ApplicationScope
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.agent.AgentApprovalCenter
import com.artemchep.keyguard.common.service.agent.AgentPrompt
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.feature.agent.rememberAgentRequestUiState
import com.artemchep.keyguard.feature.gpgagent.GpgAgentApprovalContent
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.sshagent.SshAgentApprovalContent
import com.artemchep.keyguard.platform.lifecycle.LePlatformLifecycleProvider
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.agent_title
import com.artemchep.keyguard.res.environments_approval_title
import com.artemchep.keyguard.res.gpg_client_request
import com.artemchep.keyguard.res.protocol_gpg
import com.artemchep.keyguard.res.protocol_ssh
import com.artemchep.keyguard.res.ssh_client_request
import org.jetbrains.compose.resources.stringResource

/**
 * The one window that shows the approval prompts of every agent, one at
 * a time, so concurrent prompts never cover each other.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ApplicationScope.AgentApprovalWindow(
    processLifecycleProvider: LePlatformLifecycleProvider,
    center: AgentApprovalCenter,
    environmentRepository: EnvironmentRepository,
    environmentAccess: EnvironmentAccess,
    cli: CliService,
) {
    val requestUiState = rememberAgentRequestUiState(
        pending = center.pending,
        idOf = AgentPrompt::id,
        expiresAtOf = AgentPrompt::expiresAt,
        respond = center::respond,
        logType = "agent",
    ) ?: return
    // The window names the agent of the shown prompt, and keeps that
    // name through the pause before the next prompt.
    val shown = (requestUiState as? Loadable.Ok)?.value?.request
    var lastShown by remember { mutableStateOf(shown) }
    SideEffect {
        if (shown != null) lastShown = shown
    }
    // A pause always follows a shown prompt, so there is always one to name.
    val chrome = agentPromptChrome(shown ?: lastShown ?: return)
    AgentRequestWindow(
        processLifecycleProvider = processLifecycleProvider,
        requestUiState = requestUiState,
        title = chrome.title,
        authReason = chrome.authReason,
        focusTag = "AgentApprovalWindow",
    ) { prompt, onDecision ->
        when (prompt) {
            is AgentPrompt.Ssh -> SshAgentApprovalContent(
                request = prompt.request,
                onDecision = onDecision,
            )

            is AgentPrompt.Gpg -> GpgAgentApprovalContent(
                request = prompt.request,
                onDecision = onDecision,
            )

            is AgentPrompt.Environment -> EnvironmentApprovalPrompt(
                repository = environmentRepository,
                access = environmentAccess,
                request = prompt.request,
                onDecision = onDecision,
            )

            is AgentPrompt.Cli -> CliApprovalPrompt(
                service = cli,
                request = prompt.request,
                onDecision = onDecision,
            )
        }
    }
}

private class AgentPromptChrome(
    val title: String,
    val authReason: TextHolder,
)

@Composable
private fun agentPromptChrome(prompt: AgentPrompt): AgentPromptChrome = when (prompt) {
    is AgentPrompt.Ssh -> AgentPromptChrome(
        title = stringResource(Res.string.agent_title, stringResource(Res.string.protocol_ssh)),
        authReason = TextHolder.Res(Res.string.ssh_client_request),
    )

    is AgentPrompt.Gpg -> AgentPromptChrome(
        title = stringResource(Res.string.agent_title, stringResource(Res.string.protocol_gpg)),
        authReason = TextHolder.Res(Res.string.gpg_client_request),
    )

    is AgentPrompt.Cli,
    is AgentPrompt.Environment,
    -> AgentPromptChrome(
        title = stringResource(Res.string.environments_approval_title),
        authReason = TextHolder.Res(Res.string.environments_approval_title),
    )
}
