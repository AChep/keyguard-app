package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.service.cli.agent.CliAgentAnswer
import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import com.artemchep.keyguard.common.service.cli.agent.cliAgentError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The browser extension integration of the apps that do not serve browser
 * extensions: never supported, with nothing to approve, save or pair.
 */
@Suppress("TooManyFunctions") // One no-op per operation of the service.
object UnsupportedBrowserExtension : BrowserExtensionHost, BrowserExtensionStreams {
    override val state: StateFlow<BrowserExtensionState> =
        MutableStateFlow(BrowserExtensionState()).asStateFlow()
    override val approvals: StateFlow<List<BrowserApprovalRequest>> =
        MutableStateFlow(emptyList<BrowserApprovalRequest>()).asStateFlow()
    override val saves: StateFlow<List<BrowserSaveRequest>> =
        MutableStateFlow(emptyList<BrowserSaveRequest>()).asStateFlow()

    override fun configureRuntime(supported: Boolean) = Unit

    override suspend fun setEnabled(enabled: Boolean) = Unit

    override fun respondToApproval(id: String, approved: Boolean): Boolean = false

    override fun namePairing(id: String, name: String): Boolean = false

    override fun setAddUri(id: String, add: Boolean): Boolean = false

    override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean): Boolean = false

    override fun onSaveShown(id: String) = Unit

    override fun closeSave(id: String) = Unit

    override suspend fun removePairing(id: String) = Unit

    override suspend fun renamePairing(id: String, name: String) = Unit

    override suspend fun setConnectorsRemoved(browsers: Set<BrowserConnectorBrowser>, removed: Boolean) = Unit

    /** Refuses every stream, as [BrowserExtensionServiceImpl.open] does while the integration is off. */
    override suspend fun open(request: CliAgentMessages.BrowserOpenRequest): CliAgentAnswer =
        CliAgentAnswer(cliAgentError("integration_disabled"))
}
