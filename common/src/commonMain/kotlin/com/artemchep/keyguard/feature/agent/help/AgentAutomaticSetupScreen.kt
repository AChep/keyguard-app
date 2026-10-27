package com.artemchep.keyguard.feature.agent.help

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.agent_auto_setup_apply_action
import com.artemchep.keyguard.res.agent_auto_setup_applying_status
import com.artemchep.keyguard.res.agent_auto_setup_check_action
import com.artemchep.keyguard.res.agent_auto_setup_discard_action
import com.artemchep.keyguard.res.agent_auto_setup_discard_message
import com.artemchep.keyguard.res.agent_auto_setup_discard_title
import com.artemchep.keyguard.res.agent_auto_setup_refresh_action
import com.artemchep.keyguard.res.agent_auto_setup_remove_action
import com.artemchep.keyguard.res.agent_auto_setup_review_required_note
import com.artemchep.keyguard.res.agent_auto_setup_review_retry_action
import com.artemchep.keyguard.res.agent_auto_setup_reviewing_status
import com.artemchep.keyguard.res.agent_auto_setup_step_check_detail
import com.artemchep.keyguard.res.agent_auto_setup_step_check_title
import com.artemchep.keyguard.res.agent_auto_setup_step_finish_title
import com.artemchep.keyguard.res.agent_auto_setup_step_review_detail
import com.artemchep.keyguard.res.agent_auto_setup_step_review_no_changes_detail
import com.artemchep.keyguard.res.agent_auto_setup_step_review_title
import com.artemchep.keyguard.res.agent_auto_setup_step_run_detail
import com.artemchep.keyguard.res.agent_auto_setup_step_run_title
import com.artemchep.keyguard.res.agent_auto_setup_step_settings_detail
import com.artemchep.keyguard.res.agent_auto_setup_step_settings_title
import com.artemchep.keyguard.res.agent_auto_setup_step_undo_detail
import com.artemchep.keyguard.res.agent_auto_setup_step_undo_title
import com.artemchep.keyguard.res.agent_auto_setup_wizard_title
import com.artemchep.keyguard.res.agent_auto_setup_wizard_undo_title
import com.artemchep.keyguard.res.continue_
import com.artemchep.keyguard.res.done
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.verticalPaddingHalf
import com.artemchep.keyguard.ui.wizard.WizardBackButton
import com.artemchep.keyguard.ui.wizard.WizardBottomBar
import com.artemchep.keyguard.ui.wizard.WizardDiscardDialog
import com.artemchep.keyguard.ui.wizard.WizardHeader
import com.artemchep.keyguard.ui.wizard.WizardScaffold
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AgentAutomaticSetupScreen(agent: SetupAgent, mode: AgentSetupMode) {
    AgentAutomaticSetupContent(agent, mode, produceAgentAutomaticSetupState(agent, mode))
}

@Composable
internal fun AgentAutomaticSetupContent(
    agent: SetupAgent,
    mode: AgentSetupMode,
    state: AgentAutomaticSetupState?,
) {
    val title = if (mode == AgentSetupMode.UNDO) {
        Res.string.agent_auto_setup_wizard_undo_title
    } else {
        Res.string.agent_auto_setup_wizard_title
    }
    WizardScaffold(
        title = stringResource(title, stringResource(agent.protocol)),
        step = state?.data?.step,
        navigationIcon = {
            WizardBackButton(
                onBack = state?.onBack,
                enabled = state?.data?.mutating == false,
            )
        },
        bottomBar = { state?.let { AgentWizardBottomBar(it) } },
    ) { page ->
        requireNotNull(state)
        AgentWizardHeader(state.data.copy(step = page))
        AgentWizardPage(state, page)
    }
    if (state?.data?.confirmingDiscard == true) {
        WizardDiscardDialog(
            title = stringResource(Res.string.agent_auto_setup_discard_title),
            text = stringResource(Res.string.agent_auto_setup_discard_message),
            action = stringResource(Res.string.agent_auto_setup_discard_action),
            onDiscard = state.onDiscard,
            onDismiss = state.onDismissDiscard,
        )
    }
}

@Composable
private fun AgentWizardBottomBar(state: AgentAutomaticSetupState) {
    val reviewing = state.data.reviewStatus?.recovery == true || state.data.operation == AgentSetupOperation.REVIEWING
    WizardBottomBar(
        text = stringResource(agentWizardAction(state.data)),
        onClick = state.onPrimaryAction,
        loading = state.data.busy,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        tonal = reviewing,
    ) {
        AgentWizardReviewGuidance(state.data)
    }
}

@Composable
private fun AgentWizardReviewGuidance(data: AgentSetupData) {
    val message = when (data.reviewStatus) {
        AgentSetupReviewStatus.DISABLED -> agentSetupEnableMessage(data.agent)
        AgentSetupReviewStatus.REQUIRED -> stringResource(Res.string.agent_auto_setup_review_required_note)
        else -> null
    }
    message?.let {
        Text(
            text = it,
            modifier = Modifier.padding(horizontal = Dimens.textHorizontalPadding)
                .padding(bottom = Dimens.verticalPaddingHalf),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun agentWizardAction(data: AgentSetupData): StringResource = when {
    !data.available || data.step == AgentSetupStep.RESULT -> Res.string.done
    data.operation == AgentSetupOperation.REVIEWING -> Res.string.agent_auto_setup_reviewing_status
    data.operation == AgentSetupOperation.APPLYING -> Res.string.agent_auto_setup_applying_status
    data.step == AgentSetupStep.SETTINGS || data.external -> Res.string.continue_
    data.reviewStatus == AgentSetupReviewStatus.FAILED -> Res.string.agent_auto_setup_review_retry_action
    data.reviewStatus?.recovery == true -> Res.string.agent_auto_setup_refresh_action
    data.mode == AgentSetupMode.UNDO -> Res.string.agent_auto_setup_remove_action
    data.plan?.edits?.isEmpty() == true -> Res.string.agent_auto_setup_check_action
    else -> Res.string.agent_auto_setup_apply_action
}

@Composable
private fun AgentWizardHeader(data: AgentSetupData) {
    WizardHeader(
        stepNumber = data.stepNumber,
        stepCount = data.stepCount,
        title = stringResource(agentWizardHeading(data)),
        detail = agentWizardDescription(data)?.let { stringResource(it) },
    )
}

internal fun agentWizardHeading(data: AgentSetupData): StringResource = when {
    data.step == AgentSetupStep.SETTINGS -> Res.string.agent_auto_setup_step_settings_title
    data.step == AgentSetupStep.RESULT && data.mode == AgentSetupMode.UNDO ->
        Res.string.agent_auto_setup_step_finish_title
    data.step == AgentSetupStep.RESULT -> Res.string.agent_auto_setup_step_check_title
    data.mode == AgentSetupMode.UNDO -> Res.string.agent_auto_setup_step_undo_title
    data.external -> Res.string.agent_auto_setup_step_run_title
    else -> Res.string.agent_auto_setup_step_review_title
}

internal fun agentWizardDescription(data: AgentSetupData): StringResource? = when {
    data.step == AgentSetupStep.SETTINGS -> Res.string.agent_auto_setup_step_settings_detail
    data.step == AgentSetupStep.RESULT && (data.external || data.mode == AgentSetupMode.UNDO) -> null
    data.step == AgentSetupStep.RESULT -> Res.string.agent_auto_setup_step_check_detail
    data.external -> Res.string.agent_auto_setup_step_run_detail
    data.mode == AgentSetupMode.UNDO -> Res.string.agent_auto_setup_step_undo_detail
    data.reviewStatus == AgentSetupReviewStatus.READY && data.plan?.edits?.isEmpty() == true ->
        Res.string.agent_auto_setup_step_review_no_changes_detail
    else -> Res.string.agent_auto_setup_step_review_detail
}
