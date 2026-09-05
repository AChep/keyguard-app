package com.artemchep.keyguard.feature.gpgagent

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.feature.agent.AgentApprovalContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_agent_request_approval_decrypt_message_known_app
import com.artemchep.keyguard.res.gpg_agent_request_approval_decrypt_message_unknown_app
import com.artemchep.keyguard.res.gpg_agent_request_approval_decrypt_title
import com.artemchep.keyguard.res.gpg_agent_request_approval_sign_message_known_app
import com.artemchep.keyguard.res.gpg_agent_request_approval_sign_message_unknown_app
import com.artemchep.keyguard.res.gpg_agent_request_approval_sign_title

@Composable
fun GpgAgentApprovalContent(
    request: GpgAgentApprovalRequest,
    onDismiss: () -> Unit,
) {
    val title = when (request.operation) {
        GpgAgentOperation.SIGN -> Res.string.gpg_agent_request_approval_sign_title
        GpgAgentOperation.DECRYPT -> Res.string.gpg_agent_request_approval_decrypt_title
    }
    val messageKnownApp = when (request.operation) {
        GpgAgentOperation.SIGN -> Res.string.gpg_agent_request_approval_sign_message_known_app
        GpgAgentOperation.DECRYPT -> Res.string.gpg_agent_request_approval_decrypt_message_known_app
    }
    val messageUnknownApp = when (request.operation) {
        GpgAgentOperation.SIGN -> Res.string.gpg_agent_request_approval_sign_message_unknown_app
        GpgAgentOperation.DECRYPT -> Res.string.gpg_agent_request_approval_decrypt_message_unknown_app
    }
    AgentApprovalContent(
        request = request,
        title = title,
        messageKnownApp = messageKnownApp,
        messageUnknownApp = messageUnknownApp,
        keyName = request.keyName,
        keyFingerprint = request.keyFingerprint.ifBlank { request.keygrip },
        cipherId = request.cipherId,
        onDismiss = onDismiss,
    )
}
