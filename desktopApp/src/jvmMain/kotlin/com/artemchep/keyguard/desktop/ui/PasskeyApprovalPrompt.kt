package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalOperation
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalRequest
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovals
import com.artemchep.keyguard.feature.passkeys.approval.PasskeyApprovalContent
import com.artemchep.keyguard.feature.passkeys.approval.toChoiceItem

/** The body of an approval prompt of the operating system's passkey request, inside the shared agent window. */
@Composable
internal fun PasskeyApprovalPrompt(
    approvals: PasskeyApprovals,
    request: PasskeyApprovalRequest,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    when (val operation = request.operation) {
        PasskeyApprovalOperation.Unlock -> UnlockApprovalPrompt(
            requestId = request.id,
            onDecision = onDecision,
        )

        is PasskeyApprovalOperation.SignIn -> PasskeySignInPrompt(
            approvals = approvals,
            request = request,
            operation = operation,
            onDecision = onDecision,
        )
    }
}

@Composable
private fun PasskeySignInPrompt(
    approvals: PasskeyApprovals,
    request: PasskeyApprovalRequest,
    operation: PasskeyApprovalOperation.SignIn,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    // The most recently used passkey comes first.
    var selected by remember(request.id) { mutableStateOf(0) }
    val choice = operation.choices.getOrNull(selected)
    ApprovalPrompt(
        requestId = request.id,
        // The confirmation of the identity follows the selected passkey.
        requiresIdentity = choice?.reprompt == true,
        expiresAt = request.expiresAt,
        confirm = { verify ->
            // A confirmation holds only for the passkey it was made
            // for, so the selection goes first.
            approvals.selectPasskey(request.id, selected)
            approvals.confirmIdentity(request.id, verify)
        },
        onDecision = onDecision,
        subject = remember(choice) { choice?.toChoiceItem() },
    ) { controls ->
        PasskeyApprovalContent(
            request = request,
            operation = operation,
            selected = selected,
            onSelect = { selected = it },
            busy = controls.busy,
            onDeny = controls.onDeny,
            onAllow = controls.onAllow,
        )
    }
}
