package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.BrowserOperation
import com.artemchep.keyguard.feature.browser.approval.BrowserApprovalContent
import com.artemchep.keyguard.feature.browser.approval.BrowserPasskeyPickContent
import com.artemchep.keyguard.feature.browser.approval.toChoiceItem

/** The body of an approval prompt of a browser extension, inside the shared agent window. */
@Composable
internal fun BrowserApprovalPrompt(
    service: BrowserExtensionService,
    request: BrowserApprovalRequest,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    when (val operation = request.operation) {
        BrowserOperation.Unlock -> UnlockApprovalPrompt(
            requestId = request.id,
            onDecision = onDecision,
        )

        is BrowserOperation.PasskeyPick -> BrowserPasskeyPickPrompt(
            service = service,
            request = request,
            operation = operation,
            onDecision = onDecision,
        )

        is BrowserOperation.Pair,
        is BrowserOperation.Release,
        -> BrowserRequestPrompt(
            service = service,
            request = request,
            operation = operation,
            onDecision = onDecision,
        )
    }
}

@Composable
private fun BrowserRequestPrompt(
    service: BrowserExtensionService,
    request: BrowserApprovalRequest,
    operation: BrowserOperation,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    var name by remember(request.id) {
        mutableStateOf((operation as? BrowserOperation.Pair)?.suggestedName.orEmpty())
    }
    // Unchecked by default.
    var addUri by remember(request.id) { mutableStateOf(false) }
    // The item that approving releases; a pairing releases none.
    val release = (operation as? BrowserOperation.Release)?.release
    ApprovalPrompt(
        requestId = request.id,
        requiresIdentity = request.requiresIdentity,
        expiresAt = request.expiresAt,
        confirm = { verify ->
            if (operation is BrowserOperation.Pair) service.namePairing(request.id, name)
            if (addUri) service.setAddUri(request.id, add = true)
            service.confirmIdentity(request.id, verify)
        },
        onDecision = onDecision,
        subject = remember(release) { release?.toChoiceItem() },
    ) { controls ->
        BrowserApprovalContent(
            request = request,
            name = name,
            onNameChange = { name = it },
            addUri = addUri,
            onAddUriChange = { addUri = it },
            busy = controls.busy,
            onDeny = controls.onDeny,
            onAllow = controls.onAllow,
        )
    }
}

@Composable
private fun BrowserPasskeyPickPrompt(
    service: BrowserExtensionService,
    request: BrowserApprovalRequest,
    operation: BrowserOperation.PasskeyPick,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    // The most recently used passkey comes first.
    var selected by remember(request.id) { mutableStateOf(0) }
    val choice = operation.choices.getOrNull(selected)
    ApprovalPrompt(
        requestId = request.id,
        // The confirmation of the identity follows the selected passkey.
        requiresIdentity = choice?.requiresIdentity ?: request.requiresIdentity,
        expiresAt = request.expiresAt,
        confirm = { verify ->
            // A confirmation holds only for the passkey it was made
            // for, so the selection goes first.
            service.selectPasskey(request.id, selected)
            service.confirmIdentity(request.id, verify)
        },
        onDecision = onDecision,
        subject = remember(choice) { choice?.release?.toChoiceItem() },
    ) { controls ->
        BrowserPasskeyPickContent(
            request = request,
            operation = operation,
            selected = selected,
            onSelect = { selected = it },
            // Denying the prompt then hands the request back to the browser.
            onAnotherDevice = {
                service.useAnotherDevice(request.id)
                onDecision(false, "another_device")
            },
            busy = controls.busy,
            onDeny = controls.onDeny,
            onAllow = controls.onAllow,
        )
    }
}
