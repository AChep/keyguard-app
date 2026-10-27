package com.artemchep.keyguard.feature.environments.approval

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.environment.EnvironmentApprovalRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentCallerIdentity
import com.artemchep.keyguard.common.service.environment.EnvironmentCapability
import com.artemchep.keyguard.common.service.environment.EnvironmentCatalog
import com.artemchep.keyguard.feature.agent.AgentApprovalDialog
import com.artemchep.keyguard.feature.agent.rememberAgentApprovalCallerInfo
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_approval_client
import com.artemchep.keyguard.res.environments_approval_local_file_note
import com.artemchep.keyguard.res.environments_approval_message_known_app
import com.artemchep.keyguard.res.environments_approval_message_unknown_app
import com.artemchep.keyguard.res.environments_approval_note
import com.artemchep.keyguard.res.environments_approval_title
import com.artemchep.keyguard.res.environments_capability_authenticate
import com.artemchep.keyguard.res.environments_capability_create
import com.artemchep.keyguard.res.environments_capability_create_mount
import com.artemchep.keyguard.res.environments_capability_list
import com.artemchep.keyguard.res.environments_capability_mounts
import com.artemchep.keyguard.res.environments_capability_read
import com.artemchep.keyguard.res.environments_capability_rename
import com.artemchep.keyguard.res.environments_capability_update
import com.artemchep.keyguard.res.environments_capability_variables
import com.artemchep.keyguard.res.ipc_approval_approve
import com.artemchep.keyguard.res.ipc_approval_deny
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.text.annotatedResource
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the content for an environment access approval window,
 * styled like the SSH and GPG agent approval windows.
 *
 * @param request The pending approval request.
 * @param state The environments state, used to name the requested
 *   environment and its account.
 * @param busy `true` while the approval is being verified.
 */
@Composable
fun EnvironmentApprovalContent(
    request: EnvironmentApprovalRequest,
    state: EnvironmentCatalog,
    busy: Boolean,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
) {
    val callerIdentity = remember(request.caller) {
        request.caller?.let(::EnvironmentCallerIdentity)
    }
    val callerInfo = rememberAgentApprovalCallerInfo(callerIdentity)
    // The client reports its own name, so it is shown apart
    // from the verified caller.
    val clientName = remember(request.caller) {
        request.caller?.clientName
            .sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH)
    }
    val clientLabel = clientName?.let { stringResource(Res.string.environments_approval_client, it) }
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
            if (who != null) {
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
        },
        callerInfo = callerInfo,
        details = listOfNotNull(clientLabel, callerInfo?.secondaryLabel),
        expiresAt = request.expiresAt,
        cancelText = stringResource(Res.string.ipc_approval_deny),
        confirmText = stringResource(Res.string.ipc_approval_approve),
        confirmEnabled = !busy,
        onCancel = onDeny,
        onConfirm = onAllow,
    ) {
        EnvironmentApprovalRequestItem(
            request = request,
            state = state,
        )
        Spacer(modifier = Modifier.height(12.dp))
        EnvironmentApprovalNote(request)
    }
}

/** What approving allows, and for how long. */
@Composable
private fun EnvironmentApprovalNote(
    request: EnvironmentApprovalRequest,
) {
    val notes = when (request.capability) {
        EnvironmentCapability.CreateLocalEnvFile,
        EnvironmentCapability.ReadLocalEnvFile,
        -> listOf(
            Res.string.environments_approval_note,
            Res.string.environments_approval_local_file_note,
        )

        else -> listOf(
            Res.string.environments_approval_note,
        )
    }
    notes.forEachIndexed { index, note ->
        if (index > 0) {
            Spacer(modifier = Modifier.height(4.dp))
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
}

@Composable
private fun EnvironmentApprovalRequestItem(
    request: EnvironmentApprovalRequest,
    state: EnvironmentCatalog,
) {
    val environment = state.environments
        .firstOrNull { it.id == request.environmentId }
    val environmentName = request.environmentName
        ?: environment?.name
    val accountId = environment?.accountId
        ?: request.accountId
    val accountName = state.accounts
        .firstOrNull { it.id == accountId }
        ?.name
    FlatItemLayoutExpressive(
        leading = {
            Icon(
                imageVector = environmentCapabilityIcon(request.capability),
                contentDescription = null,
            )
        },
        content = {
            val subtitle = listOfNotNull(environmentName, accountName)
                .joinToString(separator = " • ")
            FlatItemTextContent(
                title = {
                    Text(
                        text = stringResource(environmentCapabilityLabel(request.capability)),
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
            EnvironmentApprovalRequestDetails(request)
        },
    )
}

/** The path of a local `.env` file. */
@Composable
private fun EnvironmentApprovalRequestDetails(
    request: EnvironmentApprovalRequest,
) {
    val path = request.path ?: return
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = path,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}

private fun environmentCapabilityIcon(capability: EnvironmentCapability): ImageVector = when (capability) {
    EnvironmentCapability.Authenticate -> Icons.Outlined.Link
    EnvironmentCapability.ListEnvironments,
    EnvironmentCapability.ListVariables,
    -> Icons.AutoMirrored.Outlined.List

    EnvironmentCapability.CreateEnvironment -> Icons.Outlined.Add
    EnvironmentCapability.RenameEnvironment,
    EnvironmentCapability.AppendVariables,
    -> Icons.Outlined.Edit

    EnvironmentCapability.CreateLocalEnvFile -> Icons.AutoMirrored.Outlined.NoteAdd
    EnvironmentCapability.ListLocalEnvFiles -> Icons.Outlined.FolderOpen
    EnvironmentCapability.ReadLocalEnvFile -> Icons.Outlined.Description
}

private fun environmentCapabilityLabel(capability: EnvironmentCapability): StringResource = when (capability) {
    EnvironmentCapability.Authenticate -> Res.string.environments_capability_authenticate
    EnvironmentCapability.ListEnvironments -> Res.string.environments_capability_list
    EnvironmentCapability.CreateEnvironment -> Res.string.environments_capability_create
    EnvironmentCapability.RenameEnvironment -> Res.string.environments_capability_rename
    EnvironmentCapability.ListVariables -> Res.string.environments_capability_variables
    EnvironmentCapability.AppendVariables -> Res.string.environments_capability_update
    EnvironmentCapability.CreateLocalEnvFile -> Res.string.environments_capability_create_mount
    EnvironmentCapability.ListLocalEnvFiles -> Res.string.environments_capability_mounts
    EnvironmentCapability.ReadLocalEnvFile -> Res.string.environments_capability_read
}
