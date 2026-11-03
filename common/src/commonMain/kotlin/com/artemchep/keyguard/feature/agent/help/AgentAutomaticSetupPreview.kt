package com.artemchep.keyguard.feature.agent.help

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.artemchep.keyguard.ui.wizard.WizardPreview
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

internal fun agentSetupPreviewData(
    step: AgentSetupStep = AgentSetupStep.SETTINGS,
    agent: SetupAgent = SetupAgent.SSH,
) = AgentSetupData(
    agent = agent,
    enabled = true,
    shells = SetupShell.entries.toImmutableList(),
    step = step,
    plan = AgentSetupPlan(
        json = "",
        edits = persistentListOf(
            AgentSetupEdit("/home/alex/.ssh/config", null, "Host *\n    IdentityAgent ~/.keyguard/ssh-agent.sock"),
            AgentSetupEdit(
                "/home/alex/.zshrc",
                "# My profile",
                "# My profile\nexport SSH_AUTH_SOCK=~/.keyguard/ssh-agent.sock",
            ),
        ),
        conflicts = persistentListOf(),
        undo = false,
    ).takeIf { step == AgentSetupStep.REVIEW },
    applied = step == AgentSetupStep.RESULT,
    verified = true.takeIf { step == AgentSetupStep.RESULT },
)

internal fun agentSetupPreviewState(data: AgentSetupData) = AgentAutomaticSetupState(
    data = data,
    onContinue = {}.takeIf { data.canContinue },
    onRefresh = {}.takeIf { data.canRefresh },
    onRetry = {}.takeIf { data.canRetry },
)

@Preview
@Composable
internal fun AgentSetupSettingsPreview() {
    WizardPreview {
        AgentAutomaticSetupContent(
            SetupAgent.SSH,
            AgentSetupMode.CONFIGURE,
            agentSetupPreviewState(agentSetupPreviewData()),
        )
    }
}

@Preview
@Composable
internal fun AgentSetupReviewPreview() {
    val data = agentSetupPreviewData(AgentSetupStep.REVIEW)
    WizardPreview {
        AgentAutomaticSetupContent(data.agent, data.mode, agentSetupPreviewState(data))
    }
}

@Preview
@Composable
internal fun AgentSetupResultPreview() {
    val data = agentSetupPreviewData(AgentSetupStep.RESULT)
    WizardPreview {
        AgentAutomaticSetupContent(data.agent, data.mode, agentSetupPreviewState(data))
    }
}
