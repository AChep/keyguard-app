package com.artemchep.keyguard.feature.sshagent

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import com.artemchep.keyguard.feature.agent.AgentApprovalContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.agent_request_approval_sign_message_known_app
import com.artemchep.keyguard.res.agent_request_approval_sign_message_unknown_app
import com.artemchep.keyguard.res.agent_request_approval_sign_title
import com.artemchep.keyguard.res.protocol_ssh

/**
 * Renders the content for the SSH signing approval window.
 *
 * @param request The pending approval request.
 * @param onDecision Answers the request; the reason is only logged.
 */
@Composable
fun SshAgentApprovalContent(
    request: SshAgentApprovalRequest,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    AgentApprovalContent(
        request = request,
        protocol = Res.string.protocol_ssh,
        title = Res.string.agent_request_approval_sign_title,
        messageKnownApp = Res.string.agent_request_approval_sign_message_known_app,
        messageUnknownApp = Res.string.agent_request_approval_sign_message_unknown_app,
        keyName = request.keyName,
        keyFingerprint = request.keyFingerprint,
        cipherId = request.cipherId,
        onDecision = onDecision,
    )
}
