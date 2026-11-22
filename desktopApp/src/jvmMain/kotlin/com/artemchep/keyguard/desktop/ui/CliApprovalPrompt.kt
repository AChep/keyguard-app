package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.service.cli.CliApprovalRequest
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.feature.cli.approval.CliApprovalContent

/** The body of an approval prompt of the CLI, inside the shared agent window. */
@Composable
internal fun CliApprovalPrompt(
    service: CliService,
    request: CliApprovalRequest,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    ApprovalPrompt(
        requestId = request.id,
        requiresIdentity = request.requiresIdentity,
        expiresAt = request.expiresAt,
        confirm = { verify ->
            service.confirmIdentity(request.id, verify)
        },
        onDecision = onDecision,
    ) { controls ->
        CliApprovalContent(
            request = request,
            busy = controls.busy,
            onDeny = controls.onDeny,
            onAllow = controls.onAllow,
        )
    }
}
