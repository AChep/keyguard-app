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
        confirm = { verify ->
            service.confirmIdentity(request.id, verify)
        },
        onDecision = onDecision,
    ) { busy, onDeny, onAllow ->
        CliApprovalContent(
            request = request,
            busy = busy,
            onDeny = onDeny,
            onAllow = onAllow,
        )
    }
}
