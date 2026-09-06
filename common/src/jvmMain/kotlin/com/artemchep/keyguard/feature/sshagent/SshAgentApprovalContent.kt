package com.artemchep.keyguard.feature.sshagent

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.feature.agent.AgentApprovalContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ssh_agent_request_approval_sign_message_known_app
import com.artemchep.keyguard.res.ssh_agent_request_approval_sign_message_unknown_app
import com.artemchep.keyguard.res.ssh_agent_request_approval_sign_title

/**
 * Renders the content for the SSH signing approval window.
 *
 * @param request The pending approval request.
 * @param onDismiss Called after the request has been resolved (either
 *   approved or denied) so the caller can close the window.
 */
@Composable
fun SshAgentApprovalContent(
    request: SshAgentApprovalRequest,
    onDismiss: () -> Unit,
) {
    AgentApprovalContent(
        request = request,
        title = Res.string.ssh_agent_request_approval_sign_title,
        messageKnownApp = Res.string.ssh_agent_request_approval_sign_message_known_app,
        messageUnknownApp = Res.string.ssh_agent_request_approval_sign_message_unknown_app,
        keyName = request.keyName,
        keyFingerprint = request.keyFingerprint,
        cipherId = request.cipherId,
        onDismiss = onDismiss,
    )
}
