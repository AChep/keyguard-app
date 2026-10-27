package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentApprovalRequest
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.common.service.environment.ensureElevatedAccess
import com.artemchep.keyguard.feature.environments.approval.EnvironmentApprovalContent

/** The body of an environment approval prompt, inside the shared agent window. */
@Composable
internal fun EnvironmentApprovalPrompt(
    repository: EnvironmentRepository,
    access: EnvironmentAccess,
    request: EnvironmentApprovalRequest,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    ApprovalPrompt(
        requestId = request.id,
        confirm = { verify ->
            access.ensureElevatedAccess(request, verify)
        },
        onDecision = onDecision,
    ) { busy, onDeny, onAllow ->
        val state by repository.state.collectAsState()
        EnvironmentApprovalContent(
            request = request,
            state = state,
            busy = busy,
            onDeny = onDeny,
            onAllow = onAllow,
        )
    }
}
