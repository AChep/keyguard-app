package com.artemchep.keyguard.desktop.ui

import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.UnsupportedBrowserExtension
import com.artemchep.keyguard.common.service.cli.CliApprovalRequest
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.common.service.cli.CliServiceState
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovalRequest
import com.artemchep.keyguard.common.service.passkey.PasskeyApprovals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

// The services of a sample prompt. No agent tracks a sample, so they
// change nothing, and a confirmation always succeeds: the prompt itself
// asks for the identity that the sample requires.

internal object SampleBrowserExtensionService : BrowserExtensionService by UnsupportedBrowserExtension {
    override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean = true
}

internal object SampleCliService : CliService {
    override val state: StateFlow<CliServiceState> =
        MutableStateFlow(CliServiceState()).asStateFlow()
    override val approvals: StateFlow<List<CliApprovalRequest>> =
        MutableStateFlow(emptyList<CliApprovalRequest>()).asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) = Unit

    override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean = true

    override fun respondToApproval(id: String, approved: Boolean): Boolean = false
}

internal object SamplePasskeyApprovals : PasskeyApprovals {
    override val approvals: StateFlow<List<PasskeyApprovalRequest>> =
        MutableStateFlow(emptyList<PasskeyApprovalRequest>()).asStateFlow()
    override val verifying: Flow<Boolean> = flowOf(false)

    override fun respondToApproval(id: String, approved: Boolean): Boolean = false

    override fun selectPasskey(id: String, choice: Int): Boolean = true

    override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean = true
}
