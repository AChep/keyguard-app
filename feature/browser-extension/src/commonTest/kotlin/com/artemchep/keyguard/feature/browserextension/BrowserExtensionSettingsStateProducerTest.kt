package com.artemchep.keyguard.feature.browserextension

import com.artemchep.keyguard.common.service.browser.BrowserCaller
import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorPackaging
import com.artemchep.keyguard.common.service.browser.BrowserConnectorStatus
import com.artemchep.keyguard.common.service.browser.BrowserConnectorStatus.State
import com.artemchep.keyguard.common.service.browser.BrowserExtensionSession
import com.artemchep.keyguard.common.service.browser.BrowserExtensionState
import com.artemchep.keyguard.common.service.browser.BrowserPairing
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState.ConnectorStatus
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState.SetupConnector
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState.Status
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionStore
import com.artemchep.keyguard.feature.browserextension.ui.browserExtensionSettingsStateProducer
import com.artemchep.keyguard.feature.browserextension.ui.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserExtensionSettingsStateProducerTest {
    private val work = BrowserPairing(
        id = "work",
        name = "Work Chrome",
        origin = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
        createdAt = Instant.fromEpochSeconds(1_000),
        lastUsedAt = Instant.fromEpochSeconds(2_000),
    )
    private val home = BrowserPairing(
        id = "home",
        name = "Firefox",
        origin = "keyguard@artemchep.com",
        createdAt = Instant.fromEpochSeconds(3_000),
        lastUsedAt = Instant.fromEpochSeconds(4_000),
    )
    private val workSession = BrowserExtensionSession(
        id = "session",
        origin = work.origin,
        caller = BrowserCaller(appName = "Google Chrome"),
        verifiedHost = true,
        pairingId = work.id,
    )

    /** On macOS, Brave reads Chrome's manifest. */
    private val chromeManifest =
        "/Users/user/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.artemchep.keyguard.json"

    /** A browser that reads Chrome's manifest. */
    private fun shared(
        browser: BrowserConnectorBrowser,
        state: State,
        detected: Boolean = state != State.BROWSER_ABSENT,
    ) = connectorStatus(browser, state, detected = detected, manifest = chromeManifest)

    private fun connectors(
        vararg browsers: BrowserConnectorStatus,
        packaging: BrowserConnectorPackaging = BrowserConnectorPackaging.NATIVE,
    ) = FakeBrowserConnectorClient(
        browsers = browsers.toList(),
        packaging = packaging,
    )

    private fun connectors(
        packaging: BrowserConnectorPackaging = BrowserConnectorPackaging.NATIVE,
    ) = connectors(
        connectorStatus(BrowserConnectorBrowser.CHROME, State.INSTALLED),
        connectorStatus(BrowserConnectorBrowser.CHROMIUM, State.MISSING),
        connectorStatus(BrowserConnectorBrowser.EDGE, State.STALE),
        connectorStatus(BrowserConnectorBrowser.FIREFOX, State.BROWSER_ABSENT),
        packaging = packaging,
    )

    private fun service(
        enabled: Boolean = true,
        pairings: List<BrowserPairing> = listOf(work, home),
        sessions: List<BrowserExtensionSession> = listOf(workSession),
        removedConnectors: Set<BrowserConnectorBrowser> = emptySet(),
    ) = FakeBrowserExtensionService(
        BrowserExtensionState(
            enabled = enabled,
            supported = true,
            sessions = sessions,
            pairings = pairings,
            removedConnectors = removedConnectors,
        ),
    )

    private fun unpaired(
        sessions: List<BrowserExtensionSession> = emptyList(),
        removedConnectors: Set<BrowserConnectorBrowser> = emptySet(),
    ) = service(
        pairings = emptyList(),
        sessions = sessions,
        removedConnectors = removedConnectors,
    )

    /** @param screen Cancel it to leave the screen. */
    private fun TestScope.state(
        service: FakeBrowserExtensionService,
        connectors: FakeBrowserConnectorClient?,
        screen: Job = Job(backgroundScope.coroutineContext[Job]),
    ): StateFlow<BrowserExtensionSettingsState?> {
        val scope = CoroutineScope(
            backgroundScope.coroutineContext + screen + UnconfinedTestDispatcher(testScheduler),
        )
        return browserExtensionSettingsStateProducer(service, connectors)
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    private fun BrowserExtensionSettingsState.connector(browser: BrowserConnectorBrowser) =
        connectors!!.items.single { browser in it.browsers }

    //
    // Connectors
    //

    @Test
    fun `a row per browser found of the connectors report`() = runTest {
        val connectors = connectors()
        // Off: only reads the report.
        val state = state(service(enabled = false), connectors).value!!
        assertEquals(listOf("status"), connectors.calls)
        assertEquals(BrowserConnectorPackaging.NATIVE, state.connectors!!.packaging)
        // Firefox wasn't found.
        assertEquals(
            listOf(
                listOf(BrowserConnectorBrowser.CHROME) to ConnectorStatus.INSTALLED,
                listOf(BrowserConnectorBrowser.CHROMIUM) to ConnectorStatus.NOT_INSTALLED,
                listOf(BrowserConnectorBrowser.EDGE) to ConnectorStatus.NEEDS_REPAIR,
            ),
            state.connectors.items.map { it.browsers to it.status },
        )
        assertEquals(listOf(true, false, true), state.connectors.items.map { it.checked })
        assertTrue(state.connectors.items.all { it.onCheckedChange != null })
        assertFalse(state.connectors.busy)
        assertNull(state.connectors.error)
    }

    @Test
    fun `browsers that share a manifest share a row`() = runTest {
        val connectors = connectors(
            shared(BrowserConnectorBrowser.CHROME, State.INSTALLED),
            shared(BrowserConnectorBrowser.BRAVE, State.INSTALLED),
            shared(BrowserConnectorBrowser.VIVALDI, State.INSTALLED, detected = false),
        )
        val state = state(service(), connectors).value!!
        val item = state.connectors!!.items.single()
        // Only the browsers found.
        assertEquals(listOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.BRAVE), item.browsers)
        assertEquals(ConnectorStatus.INSTALLED, item.status)
        // Every browser of the manifest, found or not.
        assertEquals("chrome,brave,vivaldi", item.id)
    }

    @Test
    fun `the Flathub build of a browser has a row of its own`() = runTest {
        val service = service(removedConnectors = setOf(BrowserConnectorBrowser.FIREFOX_FLATPAK))
        val connectors = connectors(
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.INSTALLED),
            connectorStatus(
                BrowserConnectorBrowser.FIREFOX_FLATPAK,
                State.MISSING,
                manifest = "/home/user/.var/app/org.mozilla.firefox/.mozilla/native-messaging-hosts/" +
                    "com.artemchep.keyguard.json",
            ),
        )
        val state = state(service, connectors)
        assertEquals(
            listOf(
                listOf(BrowserConnectorBrowser.FIREFOX) to ConnectorStatus.INSTALLED,
                listOf(BrowserConnectorBrowser.FIREFOX_FLATPAK) to ConnectorStatus.NOT_INSTALLED,
            ),
            state.value!!.connectors!!.items.map { it.browsers to it.status },
        )
        state.value!!.connector(BrowserConnectorBrowser.FIREFOX_FLATPAK).onCheckedChange!!(true)
        assertEquals(listOf("status", "install firefox-flatpak"), connectors.calls)
        // It links its browser's store.
        assertEquals(BrowserExtensionStore.FIREFOX_ADD_ONS, BrowserConnectorBrowser.FIREFOX_FLATPAK.store)
        assertEquals(BrowserExtensionStore.EDGE_ADD_ONS, BrowserConnectorBrowser.EDGE_FLATPAK.store)
    }

    @Test
    fun `a row of browsers that weren't found lists them all`() = runTest {
        val connectors = connectors(
            shared(BrowserConnectorBrowser.CHROME, State.INSTALLED, detected = false),
            shared(BrowserConnectorBrowser.BRAVE, State.BROWSER_ABSENT),
        )
        val state = state(service(), connectors)
        val item = state.value!!.connectors!!.items.single()
        assertEquals(listOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.BRAVE), item.browsers)
        // It can be removed; then the row goes.
        item.onCheckedChange!!(false)
        assertEquals(listOf("status", "uninstall chrome,brave"), connectors.calls)
        assertTrue(state.value!!.connectors!!.items.isEmpty())
    }

    @Test
    fun `a missing connector of a browser that wasn't found can't be turned on`() = runTest {
        val connectors = connectors(
            shared(BrowserConnectorBrowser.CHROME, State.MISSING, detected = false),
            shared(BrowserConnectorBrowser.BRAVE, State.BROWSER_ABSENT),
        )
        val state = state(service(enabled = false), connectors).value!!
        val item = state.connectors!!.items.single()
        assertFalse(item.checked)
        assertNull(item.onCheckedChange)
    }

    @Test
    fun `turning a row off records the removal and removes the connectors of the group`() = runTest {
        val service = service()
        val connectors = connectors(
            shared(BrowserConnectorBrowser.CHROME, State.INSTALLED),
            shared(BrowserConnectorBrowser.BRAVE, State.INSTALLED, detected = false),
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.INSTALLED),
        )
        val state = state(service, connectors)
        state.value!!.connector(BrowserConnectorBrowser.CHROME).onCheckedChange!!(false)
        assertEquals(
            setOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.BRAVE),
            service.state.value.removedConnectors,
        )
        assertEquals(listOf("status", "uninstall chrome,brave"), connectors.calls)
        val chrome = state.value!!.connector(BrowserConnectorBrowser.CHROME)
        assertEquals(ConnectorStatus.NOT_INSTALLED, chrome.status)
        assertFalse(chrome.checked)
        assertEquals(ConnectorStatus.INSTALLED, state.value!!.connector(BrowserConnectorBrowser.FIREFOX).status)
    }

    @Test
    fun `turning a row on clears the removal and installs the connectors of the browsers found`() = runTest {
        val service = service(
            removedConnectors = setOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.BRAVE),
        )
        val connectors = connectors(
            shared(BrowserConnectorBrowser.CHROME, State.MISSING),
            shared(BrowserConnectorBrowser.BRAVE, State.BROWSER_ABSENT),
        )
        val state = state(service, connectors)
        // The sync leaves the removed browsers alone.
        assertEquals(listOf("status"), connectors.calls)
        state.value!!.connector(BrowserConnectorBrowser.CHROME).onCheckedChange!!(true)
        assertTrue(service.state.value.removedConnectors.isEmpty())
        assertEquals(listOf("status", "install chrome"), connectors.calls)
        val chrome = state.value!!.connector(BrowserConnectorBrowser.CHROME)
        assertEquals(ConnectorStatus.INSTALLED, chrome.status)
        assertTrue(chrome.checked)
    }

    @Test
    fun `a failed switch keeps the choice and shows its error until the next operation`() = runTest {
        val service = service(enabled = false)
        val connectors = connectors()
        val error = BrowserConnectorError(
            kind = BrowserConnectorError.Kind.FOREIGN_FILE,
            path = "/home/user/.config/microsoft-edge/NativeMessagingHosts/com.artemchep.keyguard.json",
        )
        connectors.failures[BrowserConnectorBrowser.EDGE] = error
        val state = state(service, connectors)
        state.value!!.connector(BrowserConnectorBrowser.EDGE).onCheckedChange!!(false)
        assertEquals(setOf(BrowserConnectorBrowser.EDGE), service.state.value.removedConnectors)
        assertEquals(error, state.value!!.connectors!!.error)
        assertEquals(ConnectorStatus.NEEDS_REPAIR, state.value!!.connector(BrowserConnectorBrowser.EDGE).status)
        assertFalse(state.value!!.connectors!!.busy)

        // Retrying repeats the removal.
        connectors.failures.clear()
        state.value!!.connectors!!.onRetry!!()
        assertEquals(listOf("status", "uninstall edge", "uninstall edge"), connectors.calls)
        assertNull(state.value!!.connectors!!.error)
        assertNull(state.value!!.connectors!!.onRetry)
        assertEquals(ConnectorStatus.NOT_INSTALLED, state.value!!.connector(BrowserConnectorBrowser.EDGE).status)
        assertEquals(setOf(BrowserConnectorBrowser.EDGE), service.state.value.removedConnectors)
    }

    @Test
    fun `leaving the screen doesn't stop a started switch`() = runTest {
        val service = service()
        val connectors = connectors()
        val screen = Job(backgroundScope.coroutineContext[Job])
        val state = state(service, connectors, screen)
        connectors.gate = CompletableDeferred()
        state.value!!.connector(BrowserConnectorBrowser.CHROME).onCheckedChange!!(false)
        // Left after the record, before the connector is removed.
        assertEquals(setOf(BrowserConnectorBrowser.CHROME), service.state.value.removedConnectors)
        screen.cancel()
        connectors.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(
            State.MISSING,
            connectors.browsers.single { it.browser == BrowserConnectorBrowser.CHROME }.state,
        )
    }

    @Test
    fun `a report that can't be read leaves no rows`() = runTest {
        val connectors = connectors()
        connectors.failures[null] = BrowserConnectorError(BrowserConnectorError.Kind.UPDATE_REQUIRED)
        val state = state(service(), connectors).value!!
        assertNull(state.connectors!!.packaging)
        assertTrue(state.connectors.items.isEmpty())
        assertEquals(BrowserConnectorError.Kind.UPDATE_REQUIRED, state.connectors.error?.kind)
        assertNotNull(state.connectors.onRetry)
    }

    @Test
    fun `one operation on the connectors runs at a time`() = runTest {
        val connectors = connectors().apply { gate = CompletableDeferred() }
        val service = service(enabled = false)
        val state = state(service, connectors)
        assertTrue(state.value!!.connectors!!.busy)
        // The pairings don't wait for the connectors.
        state.value!!.pairings.first().onRename!!("Laptop")
        assertEquals("Laptop", service.state.value.pairings.first().name)
        connectors.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        val chromium = state.value!!.connector(BrowserConnectorBrowser.CHROMIUM)
        assertNotNull(chromium.onCheckedChange)
        connectors.gate = CompletableDeferred()
        chromium.onCheckedChange(true)
        assertNull(state.value!!.connector(BrowserConnectorBrowser.EDGE).onCheckedChange)
        // A stale snapshot can still call the action.
        chromium.onCheckedChange(true)
        connectors.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("status", "install chromium"), connectors.calls)
        assertFalse(state.value!!.connectors!!.busy)
    }

    @Test
    fun `starting with the integration on installs and repairs the connectors`() = runTest {
        val connectors = connectors()
        val state = state(service(), connectors).value!!
        assertEquals(listOf("status", "install chromium", "install edge"), connectors.calls)
        assertTrue(state.connectors!!.items.all { it.status == ConnectorStatus.INSTALLED })
    }

    @Test
    fun `starting with the integration off only reads the report`() = runTest {
        val connectors = connectors()
        connectors.failures[null] = BrowserConnectorError(BrowserConnectorError.Kind.FAILED)
        val state = state(service(enabled = false), connectors)
        assertEquals(listOf("status"), connectors.calls)
        // Retrying doesn't install either.
        connectors.failures.clear()
        state.value!!.connectors!!.onRetry!!()
        assertEquals(listOf("status", "status"), connectors.calls)
        assertEquals(ConnectorStatus.NOT_INSTALLED, state.value!!.connector(BrowserConnectorBrowser.CHROMIUM).status)
    }

    @Test
    fun `turning the integration on installs the connectors`() = runTest {
        val service = service(enabled = false)
        val connectors = connectors()
        val state = state(service, connectors)
        service.setEnabled(true)
        assertEquals(listOf("status", "status", "install chromium", "install edge"), connectors.calls)
        assertEquals(ConnectorStatus.INSTALLED, state.value!!.connector(BrowserConnectorBrowser.CHROMIUM).status)
    }

    @Test
    fun `turning the integration on during a switch installs the connectors after it`() = runTest {
        val service = service(enabled = false)
        val connectors = connectors()
        val state = state(service, connectors)
        connectors.gate = CompletableDeferred()
        state.value!!.connector(BrowserConnectorBrowser.CHROME).onCheckedChange!!(false)
        service.setEnabled(true)
        connectors.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        // Chrome stays removed.
        assertEquals(
            listOf("status", "uninstall chrome", "status", "install chromium", "install edge"),
            connectors.calls,
        )
    }

    @Test
    fun `a sync that fails for a browser shows the error and the rest of the report`() = runTest {
        val connectors = connectors()
        val error = BrowserConnectorError(
            kind = BrowserConnectorError.Kind.FOREIGN_FILE,
            path = "/home/user/.config/microsoft-edge/NativeMessagingHosts/com.artemchep.keyguard.json",
        )
        connectors.failures[BrowserConnectorBrowser.EDGE] = error
        val state = state(service(), connectors).value!!
        assertEquals(error, state.connectors!!.error)
        assertEquals(ConnectorStatus.INSTALLED, state.connector(BrowserConnectorBrowser.CHROMIUM).status)
        assertEquals(Status.ConnectorFailed, state.status)
    }

    @Test
    fun `turning the integration off keeps the pairings and the connectors`() = runTest {
        val service = service()
        val connectors = connectors()
        val state = state(service, connectors)
        val calls = connectors.calls.toList()
        service.setEnabled(false)
        assertEquals(2, state.value!!.pairings.size)
        assertEquals(3, state.value!!.connectors!!.items.size)
        assertEquals(calls, connectors.calls)
    }

    //
    // Status
    //

    @Test
    fun `the status counts the paired and the connected browsers`() = runTest {
        val service = service()
        val state = state(service, connectors())
        assertEquals(Status.Paired(paired = 2, connected = 1), state.value!!.status)
        // An unpaired session isn't a paired browser.
        service.state.update { it.copy(sessions = it.sessions + workSession.copy(id = "new", pairingId = null)) }
        assertEquals(Status.Paired(paired = 2, connected = 1), state.value!!.status)
        service.state.update { it.copy(sessions = emptyList()) }
        assertEquals(Status.Paired(paired = 2, connected = 0), state.value!!.status)
    }

    @Test
    fun `a failed connector operation shows in the status`() = runTest {
        val connectors = connectors()
        connectors.failures[null] = BrowserConnectorError(BrowserConnectorError.Kind.HOST_MISSING)
        val state = state(service(), connectors)
        assertEquals(Status.ConnectorFailed, state.value!!.status)

        // Retrying a failed sync syncs again.
        connectors.failures.clear()
        state.value!!.connectors!!.onRetry!!()
        assertEquals(listOf("status", "status", "install chromium", "install edge"), connectors.calls)
        assertEquals(Status.Paired(paired = 2, connected = 1), state.value!!.status)
    }

    @Test
    fun `no status while the integration is off or nothing is paired`() = runTest {
        val connectors = connectors()
        connectors.failures[null] = BrowserConnectorError(BrowserConnectorError.Kind.HOST_MISSING)
        assertNull(state(service(enabled = false), connectors).value!!.status)
        assertNull(state(unpaired(), connectors()).value!!.status)
    }

    //
    // Setup
    //

    @Test
    fun `the setup shows until a browser is paired`() = runTest {
        val service = unpaired()
        val state = state(service, connectors())
        val setup = assertNotNull(state.value!!.setup)
        assertFalse(setup.extensionConnected)
        // An unpaired extension connected.
        service.state.update { it.copy(sessions = listOf(workSession.copy(pairingId = null))) }
        assertTrue(state.value!!.setup!!.extensionConnected)

        service.state.update { it.copy(pairings = listOf(work)) }
        assertNull(state.value!!.setup)
    }

    @Test
    fun `no setup while the integration is off`() = runTest {
        val service = unpaired()
        val state = state(service, connectors())
        service.setEnabled(false)
        assertNull(state.value!!.setup)
    }

    @Test
    fun `the setup has no connector step without connectors`() = runTest {
        val setup = state(unpaired(), connectors = null).value!!.setup!!
        assertNull(setup.connector)
        assertEquals(BrowserExtensionStore.entries, setup.stores)
    }

    @Test
    fun `the setup lists the browsers whose connectors are installed`() = runTest {
        val connectors = connectors(
            shared(BrowserConnectorBrowser.CHROME, State.MISSING),
            shared(BrowserConnectorBrowser.BRAVE, State.MISSING),
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.INSTALLED),
            connectorStatus(BrowserConnectorBrowser.EDGE, State.BROWSER_ABSENT),
        )
        val setup = state(unpaired(), connectors).value!!.setup!!
        assertEquals(
            SetupConnector.Installed(
                listOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.BRAVE, BrowserConnectorBrowser.FIREFOX),
            ),
            setup.connector,
        )
    }

    @Test
    fun `the setup is busy until the first connector is installed`() = runTest {
        val connectors = connectors().apply { gate = CompletableDeferred() }
        val state = state(unpaired(), connectors)
        assertEquals(SetupConnector.Busy, state.value!!.setup!!.connector)
        connectors.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertIs<SetupConnector.Installed>(state.value!!.setup!!.connector)
    }

    @Test
    fun `inside Flatpak the setup installs the connectors of the browsers found`() = runTest {
        val connectors = connectors(
            connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING),
            // The sandbox doesn't see its manifest directory.
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.BROWSER_ABSENT),
            packaging = BrowserConnectorPackaging.FLATPAK,
        )
        val state = state(unpaired(), connectors)
        assertEquals(listOf("status", "install chrome"), connectors.calls)
        assertEquals(
            SetupConnector.Installed(listOf(BrowserConnectorBrowser.CHROME)),
            state.value!!.setup!!.connector,
        )
    }

    @Test
    fun `inside Flatpak the setup says if no browser was found`() = runTest {
        val connectors = connectors(
            connectorStatus(BrowserConnectorBrowser.CHROME, State.BROWSER_ABSENT),
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.BROWSER_ABSENT),
            packaging = BrowserConnectorPackaging.FLATPAK,
        )
        val state = state(unpaired(), connectors)
        assertIs<SetupConnector.NoBrowser>(state.value!!.setup!!.connector)
    }

    @Test
    fun `the setup asks to turn on a browser if every browser was turned off`() = runTest {
        val connectors = connectors(
            connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING),
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.BROWSER_ABSENT),
        )
        val service = unpaired(removedConnectors = setOf(BrowserConnectorBrowser.CHROME))
        val setup = state(service, connectors).value!!.setup!!
        assertEquals(SetupConnector.TurnOnBrowser, setup.connector)
    }

    @Test
    fun `the setup shows a manifest that Keyguard didn't write in the way of a browser found`() = runTest {
        val foreign = connectorStatus(BrowserConnectorBrowser.CHROME, State.STALE, host = null)
        val connectors = connectors(
            foreign,
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.BROWSER_ABSENT),
        )
        val state = state(unpaired(), connectors)
        // The sync leaves it alone.
        assertEquals(listOf("status"), connectors.calls)
        val connector = assertIs<SetupConnector.Failed>(state.value!!.setup!!.connector)
        assertEquals(
            BrowserConnectorError(
                kind = BrowserConnectorError.Kind.FOREIGN_FILE,
                path = foreign.manifest,
                browser = BrowserConnectorBrowser.CHROME,
            ),
            connector.error,
        )
        // The row stays as it is.
        assertEquals(ConnectorStatus.NEEDS_REPAIR, state.value!!.connector(BrowserConnectorBrowser.CHROME).status)
        assertNull(state.value!!.connectors!!.error)

        // Retrying looks for the browsers again.
        connector.onRetry()
        assertEquals(listOf("status", "status"), connectors.calls)
    }

    @Test
    fun `a manifest left behind by a browser that is gone doesn't finish the setup`() = runTest {
        val connectors = connectors(
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.INSTALLED, detected = false),
            connectorStatus(BrowserConnectorBrowser.CHROME, State.BROWSER_ABSENT),
        )
        val state = state(unpaired(), connectors).value!!
        assertIs<SetupConnector.NoBrowser>(state.setup!!.connector)
        // Its row stays, so that it can be removed.
        val firefox = state.connector(BrowserConnectorBrowser.FIREFOX)
        assertEquals(ConnectorStatus.INSTALLED, firefox.status)
        assertNotNull(firefox.onCheckedChange)
    }

    @Test
    fun `the setup says if no browser was found`() = runTest {
        val connectors = connectors(
            connectorStatus(BrowserConnectorBrowser.CHROME, State.BROWSER_ABSENT),
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.BROWSER_ABSENT),
        )
        val state = state(unpaired(), connectors)
        val connector = assertIs<SetupConnector.NoBrowser>(state.value!!.setup!!.connector)
        // The user opened a browser.
        connectors.browsers[0] = connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING)
        connector.onRetry()
        assertEquals(listOf("status", "status", "install chrome"), connectors.calls)
        assertEquals(
            SetupConnector.Installed(listOf(BrowserConnectorBrowser.CHROME)),
            state.value!!.setup!!.connector,
        )
    }

    @Test
    fun `the setup shows a failed connector operation`() = runTest {
        val connectors = connectors()
        val error = BrowserConnectorError(BrowserConnectorError.Kind.HOST_MISSING)
        connectors.failures[null] = error
        val state = state(unpaired(), connectors)
        val connector = assertIs<SetupConnector.Failed>(state.value!!.setup!!.connector)
        assertEquals(error, connector.error)

        connectors.failures.clear()
        connector.onRetry()
        assertIs<SetupConnector.Installed>(state.value!!.setup!!.connector)
    }

    @Test
    fun `the setup links the stores of the browsers found`() = runTest {
        val connectors = connectors(
            connectorStatus(BrowserConnectorBrowser.CHROME, State.INSTALLED),
            connectorStatus(BrowserConnectorBrowser.EDGE, State.BROWSER_ABSENT),
            connectorStatus(BrowserConnectorBrowser.BRAVE, State.INSTALLED),
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.MISSING),
        )
        val setup = state(unpaired(), connectors).value!!.setup!!
        assertEquals(
            listOf(BrowserExtensionStore.CHROME_WEB_STORE, BrowserExtensionStore.FIREFOX_ADD_ONS),
            setup.stores,
        )
    }

    @Test
    fun `the setup links every store until a browser is found`() = runTest {
        val connectors = connectors(
            connectorStatus(BrowserConnectorBrowser.CHROME, State.BROWSER_ABSENT),
        ).apply { gate = CompletableDeferred() }
        val state = state(unpaired(), connectors)
        // No report yet.
        assertEquals(BrowserExtensionStore.entries, state.value!!.setup!!.stores)
        connectors.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(BrowserExtensionStore.entries, state.value!!.setup!!.stores)
    }

    //
    // Pairings
    //

    @Test
    fun `a build without connectors only lists the pairings`() = runTest {
        val state = state(service(), connectors = null).value!!
        assertNull(state.connectors)
        assertEquals(listOf("work", "home"), state.pairings.map { it.id })
    }

    @Test
    fun `pairings show whether they are connected`() = runTest {
        val state = state(service(), connectors()).value!!
        assertEquals(listOf(true, false), state.pairings.map { it.connected })
        assertEquals(listOf("Work Chrome", "Firefox"), state.pairings.map { it.name })
        assertEquals(work.lastUsedAt, state.pairings.first().lastUsedAt)
    }

    @Test
    fun `renaming a pairing trims the name and ignores a blank one`() = runTest {
        val service = service()
        val state = state(service, connectors())
        state.value!!.pairings.first().onRename!!("  Laptop  ")
        assertEquals("Laptop", service.state.value.pairings.first().name)
        assertEquals("Laptop", state.value!!.pairings.first().name)
        state.value!!.pairings.first().onRename!!("   ")
        assertEquals("Laptop", service.state.value.pairings.first().name)
    }

    @Test
    fun `removing a pairing ends its connections`() = runTest {
        val service = service()
        val state = state(service, connectors())
        state.value!!.pairings.first().onRemove!!()
        assertEquals(listOf("home"), state.value!!.pairings.map { it.id })
        assertTrue(service.state.value.sessions.isEmpty())
        assertFalse(state.value!!.pairingFailed)
    }

    @Test
    fun `leaving the screen doesn't stop a started removal of a pairing`() = runTest {
        val service = service().apply { gate = CompletableDeferred() }
        val screen = Job(backgroundScope.coroutineContext[Job])
        val state = state(service, connectors(), screen)
        state.value!!.pairings.first().onRemove!!()
        // Left while the store is busy.
        screen.cancel()
        service.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("home"), service.state.value.pairings.map { it.id })
    }

    @Test
    fun `a failed pairing operation is reported`() = runTest {
        val service = service().apply { failure = IllegalStateException() }
        val state = state(service, connectors())
        state.value!!.pairings.first().onRemove!!()
        assertTrue(state.value!!.pairingFailed)
        assertNull(state.value!!.connectors!!.error)
        assertEquals(listOf("work", "home"), state.value!!.pairings.map { it.id })

        service.failure = null
        state.value!!.pairings.first().onRemove!!()
        assertFalse(state.value!!.pairingFailed)
        assertEquals(listOf("home"), state.value!!.pairings.map { it.id })
    }
}
