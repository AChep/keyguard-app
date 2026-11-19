package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.BrowserOperation
import com.artemchep.keyguard.feature.browser.approval.BrowserApprovalContent

/** The body of an approval prompt of a browser extension, inside the shared agent window. */
@Composable
internal fun BrowserApprovalPrompt(
    service: BrowserExtensionService,
    request: BrowserApprovalRequest,
    onDecision: (approved: Boolean, reason: String) -> Unit,
) {
    val operation = request.operation
    if (operation is BrowserOperation.Unlock) {
        // The window shows the unlock screen while the vault is locked;
        // once it shows this, the vault is unlocked.
        val currentOnDecision by rememberUpdatedState(onDecision)
        LaunchedEffect(request.id) {
            currentOnDecision(true, "vault_unlocked")
        }
        return
    }

    var name by remember(request.id) {
        mutableStateOf((operation as? BrowserOperation.Pair)?.suggestedName.orEmpty())
    }
    // Unchecked by default.
    var addUri by remember(request.id) { mutableStateOf(false) }
    ApprovalPrompt(
        requestId = request.id,
        confirm = { verify ->
            if (operation is BrowserOperation.Pair) service.namePairing(request.id, name)
            if (addUri) service.setAddUri(request.id, add = true)
            service.confirmIdentity(request.id, verify)
        },
        onDecision = onDecision,
    ) { busy, onDeny, onAllow ->
        BrowserApprovalContent(
            request = request,
            name = name,
            onNameChange = { name = it },
            addUri = addUri,
            onAddUriChange = { addUri = it },
            busy = busy,
            onDeny = onDeny,
            onAllow = onAllow,
        )
    }
}
