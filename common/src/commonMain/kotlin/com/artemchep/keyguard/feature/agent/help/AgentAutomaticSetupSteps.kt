package com.artemchep.keyguard.feature.agent.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.sshagent.help.SSH_AGENT_SETUP_VERIFY_CMD_LIST
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.agent_auto_setup_applied
import com.artemchep.keyguard.res.agent_auto_setup_check_failed
import com.artemchep.keyguard.res.agent_auto_setup_check_retry_action
import com.artemchep.keyguard.res.agent_auto_setup_check_succeeded
import com.artemchep.keyguard.res.agent_auto_setup_checking_status
import com.artemchep.keyguard.res.agent_auto_setup_conflicts_title
import com.artemchep.keyguard.res.agent_auto_setup_diagnostics_title
import com.artemchep.keyguard.res.agent_auto_setup_error_details_title
import com.artemchep.keyguard.res.agent_auto_setup_external_check_text
import com.artemchep.keyguard.res.agent_auto_setup_external_unverified_note
import com.artemchep.keyguard.res.agent_auto_setup_files_to_change_plural
import com.artemchep.keyguard.res.agent_auto_setup_gpg_note
import com.artemchep.keyguard.res.agent_auto_setup_host_label
import com.artemchep.keyguard.res.agent_auto_setup_hosts_all
import com.artemchep.keyguard.res.agent_auto_setup_hosts_one
import com.artemchep.keyguard.res.agent_auto_setup_no_changes
import com.artemchep.keyguard.res.agent_auto_setup_refresh_action
import com.artemchep.keyguard.res.agent_auto_setup_ssh_config_only
import com.artemchep.keyguard.res.agent_auto_setup_ssh_note
import com.artemchep.keyguard.res.agent_auto_setup_termux_run_note
import com.artemchep.keyguard.res.agent_auto_setup_unavailable
import com.artemchep.keyguard.res.agent_auto_setup_undone
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.wizard.WizardChoice
import com.artemchep.keyguard.ui.wizard.wizardSelectedBackground
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AgentWizardPage(state: AgentAutomaticSetupState, page: AgentSetupStep) {
    if (!state.data.available) {
        FlatSimpleNote(type = SimpleNote.Type.INFO, text = stringResource(Res.string.agent_auto_setup_unavailable))
        return
    }
    if (!state.data.setupAllowed && page != AgentSetupStep.RESULT) {
        if (page != AgentSetupStep.REVIEW || state.data.external) {
            FlatSimpleNote(type = SimpleNote.Type.INFO, text = agentSetupEnableMessage(state.data.agent))
        }
        return
    }
    when (page) {
        AgentSetupStep.SETTINGS -> AgentWizardSettings(state)
        AgentSetupStep.REVIEW -> if (state.data.external) AgentWizardTermuxRun(state.data) else AgentWizardReview(state)
        AgentSetupStep.RESULT -> AgentWizardResult(state)
    }
}

@Composable
private fun AgentWizardSettings(state: AgentAutomaticSetupState) {
    val data = state.data
    if (data.agent == SetupAgent.SSH && !data.external) {
        Column(Modifier.selectableGroup()) {
            AgentHostChoice(Res.string.agent_auto_setup_hosts_all, !data.oneHost, ShapeState.START, data.editable) {
                state.onHostScopeChange(false)
            }
            AgentHostChoice(Res.string.agent_auto_setup_hosts_one, data.oneHost, ShapeState.END, data.editable) {
                state.onHostScopeChange(true)
            }
        }
        if (data.oneHost) {
            FlatTextField(
                modifier = Modifier.padding(horizontal = Dimens.fieldHorizontalPadding),
                label = stringResource(Res.string.agent_auto_setup_host_label),
                value = state.host,
                singleLine = true,
            )
        }
    }
    Column {
        data.shells.forEachIndexed { index, shell ->
            val checked = shell in data.selectedShells
            FlatItemLayoutExpressive(
                rowModifier = Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                },
                shapeState = getShapeState(data.shells, index) { _, _ -> true },
                backgroundColor = wizardSelectedBackground(checked),
                leading = { Icon(Icons.Outlined.Terminal, null) },
                content = { Text(shell.title, style = MaterialTheme.typography.titleMedium) },
                trailing = { Checkbox(checked = checked, onCheckedChange = null, enabled = data.editable) },
                onClick = { state.onShellToggle(shell) }.takeIf { data.editable },
            )
        }
    }
}

@Composable
private fun AgentHostChoice(
    title: StringResource,
    checked: Boolean,
    shape: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) = WizardChoice(
    title = stringResource(title),
    icon = Icons.Outlined.Language,
    selected = checked,
    shapeState = shape,
    enabled = enabled,
    onClick = onClick,
)

@Composable
private fun AgentWizardReview(state: AgentAutomaticSetupState) {
    val data = state.data
    if (data.operation == AgentSetupOperation.REVIEWING) KeyguardLoadingIndicator()
    data.error?.let { AgentWizardError(it) }
    data.plan?.let { plan ->
        AgentWizardSummary(data)
        if (plan.overrides.isNotEmpty()) AgentWizardOverrides(plan.overrides)
        if (plan.conflicts.isNotEmpty()) {
            FlatSimpleNote(
                type = SimpleNote.Type.WARNING,
                title = stringResource(Res.string.agent_auto_setup_conflicts_title),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding)) {
                        plan.conflicts.forEach { conflict ->
                            Text(agentSetupConflictText(conflict), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
            )
        }
        // Configure mode already says this in the step description.
        if (plan.undo && plan.edits.isEmpty() && plan.conflicts.isEmpty()) {
            FlatSimpleNote(type = SimpleNote.Type.INFO, text = stringResource(Res.string.agent_auto_setup_no_changes))
        }
        if (plan.edits.isNotEmpty()) {
            AgentSetupBodyLabel(
                pluralStringResource(
                    Res.plurals.agent_auto_setup_files_to_change_plural,
                    plan.edits.size,
                    plan.edits.size,
                ),
            )
            Column {
                plan.edits.forEachIndexed { index, edit ->
                    AgentWizardFile(edit, getShapeState(plan.edits, index) { _, _ -> true })
                }
            }
        }
        if (!plan.undo) {
            AgentWizardNotes(data.agent)
        }
    }
    if (data.reviewStatus == AgentSetupReviewStatus.READY) {
        TextButton(
            modifier = Modifier.padding(horizontal = Dimens.fieldHorizontalPadding),
            onClick = { state.onRefresh?.invoke() },
            enabled = state.onRefresh != null,
        ) { Text(stringResource(Res.string.agent_auto_setup_refresh_action)) }
    }
}

@Composable
private fun AgentWizardSummary(data: AgentSetupData) {
    if (data.mode == AgentSetupMode.UNDO) return
    val hosts = if (data.oneHost) data.host.trim() else stringResource(Res.string.agent_auto_setup_hosts_all)
    val shells = data.selectedShells.joinToString { it.title }.ifEmpty {
        stringResource(Res.string.agent_auto_setup_ssh_config_only)
    }
    FlatItemLayoutExpressive(
        leading = { Icon(Icons.Outlined.Terminal, null) },
        content = {
            Text(shells, style = MaterialTheme.typography.titleMedium)
            if (data.agent == SetupAgent.SSH) Text(hosts, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        enabled = true,
    )
}

@Composable
private fun AgentWizardNotes(agent: SetupAgent) {
    val color = LocalContentColor.current.combineAlpha(MediumEmphasisAlpha)
    Column(
        modifier = Modifier.padding(horizontal = Dimens.textHorizontalPadding, vertical = Dimens.verticalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = color)
        val note = when (agent) {
            SetupAgent.SSH -> Res.string.agent_auto_setup_ssh_note
            SetupAgent.GPG -> Res.string.agent_auto_setup_gpg_note
        }
        Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun AgentWizardError(error: AgentSetupError) {
    FlatSimpleNote(type = SimpleNote.Type.ERROR, text = agentSetupErrorText(error))
    error.detail?.let { detail ->
        AgentWizardDetails(title = stringResource(Res.string.agent_auto_setup_error_details_title)) {
            AgentSetupCodeBlock(detail)
        }
    }
}

@Composable
private fun AgentWizardDetails(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column {
        FlatItemLayoutExpressive(
            leading = { Icon(Icons.Outlined.Description, null) },
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
            },
            trailing = { DropdownIcon(expanded = expanded) },
            onClick = { expanded = !expanded },
        )
        ExpandedIfNotEmpty(Unit.takeIf { expanded }) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding), content = content)
        }
    }
}

@Composable
private fun AgentWizardTermuxRun(data: AgentSetupData) {
    if (data.mode == AgentSetupMode.CONFIGURE) {
        AgentSetupParagraph(stringResource(Res.string.agent_auto_setup_termux_run_note))
    }
    AgentSetupCodeBlock(termuxSetupCommand(data))
}

@Composable
private fun AgentWizardResult(state: AgentAutomaticSetupState) {
    val data = state.data
    if (data.external) {
        FlatSimpleNote(
            type = SimpleNote.Type.INFO,
            text = stringResource(Res.string.agent_auto_setup_external_unverified_note),
        )
        if (data.mode == AgentSetupMode.CONFIGURE) {
            val command = if (data.agent == SetupAgent.SSH) SSH_AGENT_SETUP_VERIFY_CMD_LIST else {
                "gpg-connect-agent --no-autostart 'KEYINFO --list' /bye"
            }
            AgentSetupParagraph(stringResource(Res.string.agent_auto_setup_external_check_text))
            AgentSetupCodeBlock(command)
        }
        return
    }
    FlatSimpleNote(
        type = SimpleNote.Type.OK,
        text = stringResource(when {
            data.mode == AgentSetupMode.UNDO -> Res.string.agent_auto_setup_undone
            data.applied -> Res.string.agent_auto_setup_applied
            else -> Res.string.agent_auto_setup_no_changes
        }),
    )
    if (data.operation == AgentSetupOperation.VERIFYING) {
        KeyguardLoadingIndicator()
        AgentSetupParagraph(stringResource(Res.string.agent_auto_setup_checking_status))
    }
    if (data.verified == true) {
        FlatSimpleNote(type = SimpleNote.Type.OK, text = stringResource(Res.string.agent_auto_setup_check_succeeded))
    }
    if (data.verified == false) {
        FlatSimpleNote(type = SimpleNote.Type.WARNING, text = stringResource(Res.string.agent_auto_setup_check_failed))
        TextButton(
            modifier = Modifier.padding(horizontal = Dimens.fieldHorizontalPadding),
            onClick = { state.onRetry?.invoke() },
            enabled = state.onRetry != null,
        ) { Text(stringResource(Res.string.agent_auto_setup_check_retry_action)) }
    }
    (data.error?.detail ?: data.diagnostic)?.let { diagnostic ->
        AgentWizardDetails(title = stringResource(Res.string.agent_auto_setup_diagnostics_title)) {
            AgentSetupCodeBlock(diagnostic)
        }
    }
}
