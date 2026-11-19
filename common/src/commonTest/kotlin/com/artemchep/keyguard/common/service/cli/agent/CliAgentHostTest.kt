package com.artemchep.keyguard.common.service.cli.agent

import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserSaveRequest
import com.artemchep.keyguard.common.service.browser.BrowserExtensionHost
import com.artemchep.keyguard.common.service.browser.BrowserExtensionState
import com.artemchep.keyguard.common.service.cli.CliCommandDispatcher
import com.artemchep.keyguard.common.service.environment.EnvironmentFixture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CliAgentHostTest {
    @Test
    fun `the browser integration needs the agent while it is on and supported`() = runTest {
        val fixture = EnvironmentFixture(backgroundScope)
        val browser = FakeBrowserHost()
        val streams = mutableListOf<CliAgentMessages.BrowserOpenRequest>()
        val host = CliAgentHost(
            cli = fixture.cli,
            mcp = fixture.mcp,
            mounts = fixture.mounts,
            browser = browser,
            commands = CliCommandDispatcher(fixture.cli, emptyList()),
            environments = { fixture.broker(it) },
            browserStreams = { request ->
                streams += request
                CliAgentAnswer(cliAgentError("too_many_sessions"))
            },
            recorder = fixture.recorder,
        )
        assertEquals(false, host.needsAgent.first())

        browser.state.value = BrowserExtensionState(enabled = true)
        assertEquals(false, host.needsAgent.first())
        browser.state.value = BrowserExtensionState(enabled = true, supported = true)
        assertEquals(true, host.needsAgent.first())
        browser.state.value = BrowserExtensionState(enabled = false, supported = true)
        assertEquals(false, host.needsAgent.first())

        // The agent's requests to open a stream reach the browser service.
        val response = host.createRequestHandler().respond(browserOpenRequest())
        assertEquals("too_many_sessions", response.error())
        assertEquals("chrome-extension://abcdefghijklmnopabcdefghijklmnop/", streams.single().origin)
    }

    private class FakeBrowserHost : BrowserExtensionHost {
        override val state = MutableStateFlow(BrowserExtensionState())
        override val approvals: StateFlow<List<BrowserApprovalRequest>> = MutableStateFlow(emptyList())
        override val saves: StateFlow<List<BrowserSaveRequest>> = MutableStateFlow(emptyList())

        override fun configureRuntime(supported: Boolean) {
            state.value = state.value.copy(supported = supported)
        }

        override suspend fun setEnabled(enabled: Boolean) {
            state.value = state.value.copy(enabled = enabled)
        }

        override fun respondToApproval(id: String, approved: Boolean) = false

        override fun namePairing(id: String, name: String) = false

        override fun setAddUri(id: String, add: Boolean) = false

        override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean) = false

        override fun onSaveShown(id: String) = Unit

        override fun closeSave(id: String) = Unit

        override suspend fun removePairing(id: String) = Unit

        override suspend fun renamePairing(id: String, name: String) = Unit

        override suspend fun setConnectorsRemoved(browsers: Set<BrowserConnectorBrowser>, removed: Boolean) = Unit
    }
}
