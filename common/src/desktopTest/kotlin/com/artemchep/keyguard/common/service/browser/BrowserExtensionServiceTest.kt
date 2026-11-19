package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.service.cli.agent.CliAgentMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserExtensionServiceTest {
    @Test
    fun `a stream is refused while the integration is off, or unsupported`() = serviceTest { fixture ->
        fixture.service.setEnabled(false)
        assertEquals("integration_disabled", fixture.open(fixture.openRequest()))
        // The integration off comes first.
        assertEquals("integration_disabled", fixture.open(fixture.openRequest(hostRevision = 2)))

        fixture.service.setEnabled(true)
        fixture.service.configureRuntime(supported = false)
        assertEquals("integration_disabled", fixture.open(fixture.openRequest()))

        // The connector retries every 2 seconds: no retry reaches the request log.
        repeat(3) { fixture.open(fixture.openRequest()) }
        delay(100)
        assertTrue(fixture.recorded().isEmpty())
    }

    @Test
    fun `a connector of another revision must be updated`() = serviceTest { fixture ->
        for (revision in listOf(0, 2)) {
            assertEquals("update_required", fixture.open(fixture.openRequest(hostRevision = revision)))
        }
        // Before the session cap.
        repeat(8) { fixture.open(fixture.openRequest()) }
        assertEquals("update_required", fixture.open(fixture.openRequest(hostRevision = 2)))
    }

    @Test
    fun `an origin the agent should have refused is invalid`() = serviceTest { fixture ->
        assertEquals("invalid_request", fixture.open(fixture.openRequest(origin = "https://example.com/")))
    }

    @Test
    fun `the app serves at most 8 sessions`() = serviceTest { fixture ->
        val streams = List(8) {
            val answer = fixture.service.open(fixture.openRequest())
            assertIs<CliAgentMessages.Response.Kind.BrowserOpen>(answer.response)
            assertNotNull(answer.upgrade)
        }
        assertEquals(8, fixture.service.state.value.sessions.size)
        assertEquals("too_many_sessions", fixture.open(fixture.openRequest()))

        // A stream that is never served frees its slot as it closes.
        streams.first().close()
        assertEquals(7, fixture.service.state.value.sessions.size)
        assertNull(fixture.open(fixture.openRequest()))
    }

    @Test
    fun `the session shows the caller and the verified host`() = serviceTest { fixture ->
        val answer = fixture.service.open(fixture.openRequest(origin = FIREFOX_ORIGIN))
        val session = fixture.service.state.value.sessions.single()
        assertEquals(FIREFOX_ORIGIN, session.origin)
        assertEquals("Google Chrome", session.caller.appName)
        assertEquals(42, session.caller.pid)
        assertTrue(session.verifiedHost)
        assertNull(session.pairingId)
        assertNotNull(answer.upgrade).close()
        assertTrue(fixture.service.state.value.sessions.isEmpty())
    }

    @Test
    fun `a session without a caller shows no browser`() = serviceTest { fixture ->
        val answer = fixture.service.open(fixture.openRequest(caller = null))
        val session = fixture.service.state.value.sessions.single()
        assertEquals(BrowserCaller(), session.caller)
        assertTrue(session.verifiedHost)
        assertNotNull(answer.upgrade).close()
    }

    @Test
    fun `the removed connectors are stored and show in the state`() = serviceTest { fixture ->
        val chrome = setOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.BRAVE)
        val all = chrome + BrowserConnectorBrowser.FIREFOX
        fixture.service.setConnectorsRemoved(chrome, removed = true)
        fixture.service.setConnectorsRemoved(setOf(BrowserConnectorBrowser.FIREFOX), removed = true)
        assertEquals(all, fixture.removedConnectors.value)
        fixture.awaitRemovedConnectors(all)

        fixture.service.setConnectorsRemoved(chrome, removed = false)
        val firefox = setOf(BrowserConnectorBrowser.FIREFOX)
        assertEquals(firefox, fixture.removedConnectors.value)
        fixture.awaitRemovedConnectors(firefox)
    }

    /** Waits for the state to show [expected]; the store's own update may come a moment later. */
    private suspend fun BrowserFixture.awaitRemovedConnectors(expected: Set<BrowserConnectorBrowser>) {
        withTimeout(TIMEOUT) {
            service.state.first { it.removedConnectors == expected }
        }
    }

    /**
     * The error code of the agent's request, `null` once a stream is open. An
     * open stream keeps its slot; a refused one comes without a stream.
     */
    private suspend fun BrowserFixture.open(request: CliAgentMessages.BrowserOpenRequest): String? {
        val answer = service.open(request)
        return when (val response = answer.response) {
            is CliAgentMessages.Response.Kind.Error -> response.value.code.also { assertNull(answer.upgrade) }
            is CliAgentMessages.Response.Kind.BrowserOpen -> null
            else -> error("Unexpected $response")
        }
    }

    private fun serviceTest(block: suspend (BrowserFixture) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fixture = BrowserFixture(scope)
            // The setting loads on another thread.
            fixture.service.state.first { it.needsAgent }
            block(fixture)
        } finally {
            scope.cancel()
        }
    }
}
