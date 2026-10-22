package com.artemchep.keyguard.feature.cli.approval

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.cli.CliApprovalRequest
import com.artemchep.keyguard.common.service.cli.CliCallerIdentity
import com.artemchep.keyguard.common.service.cli.CliOperation
import com.artemchep.keyguard.common.service.cli.displayCommand
import com.artemchep.keyguard.common.service.cli.displayWorkingDirectory
import com.artemchep.keyguard.feature.agent.AgentApprovalDialog
import com.artemchep.keyguard.feature.agent.rememberAgentApprovalCallerInfo
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cli_approval_run_note
import com.artemchep.keyguard.res.cli_approval_run_terminal_note
import com.artemchep.keyguard.res.cli_approval_working_directory
import com.artemchep.keyguard.res.cli_capability_run
import com.artemchep.keyguard.res.environments_approval_message_known_app
import com.artemchep.keyguard.res.environments_approval_message_unknown_app
import com.artemchep.keyguard.res.environments_approval_title
import com.artemchep.keyguard.res.ipc_approval_approve
import com.artemchep.keyguard.res.ipc_approval_deny
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.text.annotatedResource
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the content for an approval window of the CLI, styled like the
 * SSH and GPG agent approval windows.
 *
 * @param request The pending approval request.
 * @param busy `true` while the approval is being verified.
 */
@Composable
fun CliApprovalContent(
    request: CliApprovalRequest,
    busy: Boolean,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
) {
    val callerIdentity = remember(request.caller) {
        CliCallerIdentity(request.caller)
    }
    val callerInfo = rememberAgentApprovalCallerInfo(callerIdentity)
    AgentApprovalDialog(
        icon = {
            Icon(
                modifier = Modifier
                    .fillMaxSize(),
                imageVector = Icons.Outlined.Code,
                contentDescription = null,
            )
        },
        iconBadgePadding = 0.dp,
        title = stringResource(Res.string.environments_approval_title),
        message = { who ->
            when (request.operation) {
                is CliOperation.Run -> if (who != null) {
                    annotatedResource(
                        Res.string.environments_approval_message_known_app,
                        who to SpanStyle(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                } else {
                    annotatedResource(
                        Res.string.environments_approval_message_unknown_app,
                    )
                }
            }
        },
        callerInfo = callerInfo,
        details = listOfNotNull(callerInfo?.secondaryLabel),
        expiresAt = request.expiresAt,
        cancelText = stringResource(Res.string.ipc_approval_deny),
        confirmText = stringResource(Res.string.ipc_approval_approve),
        confirmEnabled = !busy,
        onCancel = onDeny,
        onConfirm = onAllow,
    ) {
        when (val operation = request.operation) {
            is CliOperation.Run -> CliRunItem(operation)
        }
        Spacer(modifier = Modifier.height(12.dp))
        CliApprovalNote(request)
    }
}

/** What approving allows, and for how long. */
@Composable
private fun CliApprovalNote(
    request: CliApprovalRequest,
) {
    val note = when (request.operation) {
        is CliOperation.Run -> if (request.terminalSession) {
            Res.string.cli_approval_run_terminal_note
        } else {
            Res.string.cli_approval_run_note
        }
    }
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(note),
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}

@Composable
private fun CliRunItem(
    operation: CliOperation.Run,
) {
    FlatItemLayoutExpressive(
        leading = {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
            )
        },
        content = {
            val subtitle = listOfNotNull(operation.environmentName, operation.accountName)
                .joinToString(separator = " • ")
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(Res.string.cli_capability_run),
                    )
                },
                text = if (subtitle.isNotEmpty()) {
                    // composable
                    {
                        Text(
                            text = subtitle,
                        )
                    }
                } else {
                    null
                },
            )
            CliRunDetails(operation)
        },
    )
}

/** The command `keyguard run` starts, and where. */
@Composable
private fun CliRunDetails(
    operation: CliOperation.Run,
) {
    val command = remember(operation.command) {
        operation.displayCommand()
    }
    val workingDirectory = remember(operation.workingDirectory) {
        operation.displayWorkingDirectory()
    }
    if (command != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = command,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
    if (workingDirectory != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.cli_approval_working_directory, workingDirectory),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}
