package com.artemchep.keyguard.feature.passkeys.approval

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalChoice
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalOperation
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalRequest
import com.artemchep.keyguard.feature.agent.AgentApprovalChoiceList
import com.artemchep.keyguard.feature.agent.AgentApprovalDialog
import com.artemchep.keyguard.feature.agent.agentApprovalChoiceItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_passkey_get_title
import com.artemchep.keyguard.res.ipc_approval_approve
import com.artemchep.keyguard.res.ipc_approval_deny
import com.artemchep.keyguard.res.passkey_approval_sign_in_message
import com.artemchep.keyguard.ui.text.annotatedResource
import org.jetbrains.compose.resources.stringResource

/**
 * Renders the content of an approval window of the operating system's
 * passkey request: signing in to a site with one of the passkeys, which
 * the user picks. The operating system names no caller.
 *
 * @param operation The [request]'s operation.
 * @param selected The index of the selected passkey.
 * @param busy `true` while the approval is being verified.
 */
@Composable
fun PasskeyApprovalContent(
    request: PasskeyApprovalRequest,
    operation: PasskeyApprovalOperation.SignIn,
    selected: Int,
    onSelect: (Int) -> Unit,
    busy: Boolean,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
) {
    val items = remember(operation.choices) {
        operation.choices.map { it.toChoiceItem() }
    }
    val site = remember(request.rpId) {
        request.rpId.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_APP_BUNDLE_PATH_LENGTH).orEmpty()
    }
    AgentApprovalDialog(
        icon = {
            Icon(
                imageVector = Icons.Outlined.Key,
                contentDescription = null,
            )
        },
        iconCompact = true,
        title = stringResource(Res.string.browser_extension_passkey_get_title),
        message = {
            annotatedResource(
                Res.string.passkey_approval_sign_in_message,
                site to SpanStyle(
                    fontWeight = FontWeight.Bold,
                ),
            )
        },
        callerInfo = null,
        expiresAt = request.expiresAt,
        cancelText = stringResource(Res.string.ipc_approval_deny),
        confirmText = stringResource(Res.string.ipc_approval_approve),
        confirmEnabled = !busy,
        onCancel = onDeny,
        onConfirm = onAllow,
    ) {
        AgentApprovalChoiceList(
            items = items,
            selected = selected.coerceIn(items.indices),
            onSelect = onSelect.takeUnless { busy },
        )
    }
}

/** The passkey's name, and its username and account, as an approval prompt shows them. */
fun PasskeyApprovalChoice.toChoiceItem() = agentApprovalChoiceItem(name, username, accountName)
