package com.artemchep.keyguard.feature.browserextension

import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorException
import com.artemchep.keyguard.common.service.browser.BrowserConnectorPackaging
import com.artemchep.keyguard.common.service.browser.BrowserConnectorReport
import com.artemchep.keyguard.common.service.browser.BrowserConnectorStatus.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserConnectorSyncTest {
    @Test
    fun `installs the missing connectors of the browsers found`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING),
                // Not found.
                connectorStatus(BrowserConnectorBrowser.CHROMIUM, State.BROWSER_ABSENT),
                connectorStatus(BrowserConnectorBrowser.EDGE, State.INSTALLED),
                connectorStatus(BrowserConnectorBrowser.FIREFOX, State.MISSING),
            ),
        )
        val result = syncBrowserConnectors(client, removed = { emptySet() })
        assertEquals(setOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.FIREFOX), result.installed)
        assertEquals(emptyMap(), result.failed)
        assertEquals(listOf("status", "install chrome", "install firefox"), client.calls)
    }

    @Test
    fun `repairs the stale connectors that Keyguard installed`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(BrowserConnectorBrowser.CHROME, State.STALE),
                connectorStatus(BrowserConnectorBrowser.EDGE, State.INSTALLED),
                // A manifest that Keyguard didn't write.
                connectorStatus(BrowserConnectorBrowser.BRAVE, State.STALE, host = null),
                // A manifest of a browser that is gone.
                connectorStatus(BrowserConnectorBrowser.VIVALDI, State.STALE, detected = false),
                connectorStatus(BrowserConnectorBrowser.FIREFOX, State.STALE),
            ),
        )
        val result = syncBrowserConnectors(client, removed = { emptySet() })
        assertEquals(setOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.FIREFOX), result.installed)
        assertEquals(listOf("status", "install chrome", "install firefox"), client.calls)
    }

    @Test
    fun `skips the removed browsers and the browsers that share their manifest`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING, manifest = CHROME_MANIFEST),
                connectorStatus(BrowserConnectorBrowser.BRAVE, State.MISSING, manifest = CHROME_MANIFEST),
                connectorStatus(BrowserConnectorBrowser.EDGE, State.MISSING),
                connectorStatus(BrowserConnectorBrowser.FIREFOX, State.STALE),
            ),
        )
        val removed = setOf(BrowserConnectorBrowser.BRAVE, BrowserConnectorBrowser.FIREFOX)
        val result = syncBrowserConnectors(client, removed = { removed })
        assertEquals(setOf(BrowserConnectorBrowser.EDGE), result.installed)
        assertEquals(listOf("status", "install edge"), client.calls)
    }

    @Test
    fun `installs a shared manifest once, for the browsers found`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(
                    BrowserConnectorBrowser.CHROME,
                    State.BROWSER_ABSENT,
                    manifest = CHROME_MANIFEST,
                    registryKey = CHROME_REGISTRY_KEY,
                ),
                connectorStatus(
                    BrowserConnectorBrowser.BRAVE,
                    State.MISSING,
                    manifest = CHROME_MANIFEST,
                    registryKey = CHROME_REGISTRY_KEY,
                ),
                connectorStatus(
                    BrowserConnectorBrowser.VIVALDI,
                    State.MISSING,
                    manifest = CHROME_MANIFEST,
                    registryKey = CHROME_REGISTRY_KEY,
                ),
            ),
        )
        val result = syncBrowserConnectors(client, removed = { emptySet() })
        assertEquals(setOf(BrowserConnectorBrowser.BRAVE, BrowserConnectorBrowser.VIVALDI), result.installed)
        assertEquals(listOf("status", "install brave,vivaldi"), client.calls)
    }

    @Test
    fun `inside Flatpak, installs like the native build`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                // Inside Flatpak, found if the sandbox sees the browser's manifest directory.
                connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING),
                connectorStatus(BrowserConnectorBrowser.EDGE, State.BROWSER_ABSENT),
                connectorStatus(BrowserConnectorBrowser.FIREFOX, State.STALE),
            ),
            packaging = BrowserConnectorPackaging.FLATPAK,
        )
        val result = syncBrowserConnectors(client, removed = { emptySet() })
        assertEquals(setOf(BrowserConnectorBrowser.CHROME, BrowserConnectorBrowser.FIREFOX), result.installed)
        assertEquals(listOf("status", "install chrome", "install firefox"), client.calls)
    }

    @Test
    fun `a connector removed after the report isn't installed`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING),
                connectorStatus(BrowserConnectorBrowser.FIREFOX, State.MISSING),
            ),
        ).apply { gate = CompletableDeferred() }
        var removed = emptySet<BrowserConnectorBrowser>()
        val sync = async { syncBrowserConnectors(client, removed = { removed }) }
        testScheduler.runCurrent()
        assertEquals(listOf("status"), client.calls)
        // The user removes Firefox's connector while the report is read.
        removed = setOf(BrowserConnectorBrowser.FIREFOX)
        client.gate!!.complete(Unit)
        val result = sync.await()
        assertEquals(setOf(BrowserConnectorBrowser.CHROME), result.installed)
        assertEquals(listOf("status", "install chrome"), client.calls)
    }

    @Test
    fun `stops installing once disabled`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING),
                connectorStatus(BrowserConnectorBrowser.FIREFOX, State.MISSING),
            ),
        )
        val result = syncBrowserConnectors(
            client = client,
            removed = { emptySet() },
            // The user turns the integration off while Chrome's connector installs.
            enabled = { "install chrome" !in client.calls },
        )
        assertEquals(setOf(BrowserConnectorBrowser.CHROME), result.installed)
        assertEquals(listOf("status", "install chrome"), client.calls)
    }

    @Test
    fun `an install that waits for another operation is skipped once disabled`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING)),
        ).apply { busy = CompletableDeferred() }
        var enabled = true
        val sync = async { syncBrowserConnectors(client, removed = { emptySet() }, enabled = { enabled }) }
        testScheduler.runCurrent()
        assertEquals(listOf("status"), client.calls)
        // The user turns the integration off while the install waits.
        enabled = false
        client.busy!!.complete(Unit)
        assertEquals(emptySet(), sync.await().installed)
        assertEquals(listOf("status"), client.calls)
    }

    @Test
    fun `an install that waits for another operation is skipped once its connector is removed`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING, manifest = CHROME_MANIFEST),
                connectorStatus(BrowserConnectorBrowser.BRAVE, State.MISSING, manifest = CHROME_MANIFEST),
            ),
        ).apply { busy = CompletableDeferred() }
        var removed = emptySet<BrowserConnectorBrowser>()
        val sync = async { syncBrowserConnectors(client, removed = { removed }) }
        testScheduler.runCurrent()
        assertEquals(listOf("status"), client.calls)
        // The user turns the row off: the removal is recorded while the install waits.
        removed = setOf(BrowserConnectorBrowser.BRAVE)
        client.busy!!.complete(Unit)
        assertEquals(emptySet(), sync.await().installed)
        assertEquals(listOf("status"), client.calls)
    }

    @Test
    fun `a failure leaves the other browsers installed`() = runTest {
        val error = BrowserConnectorError(BrowserConnectorError.Kind.UNSAFE_FILE, path = "/home/user/.config")
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING, manifest = CHROME_MANIFEST),
                connectorStatus(BrowserConnectorBrowser.BRAVE, State.MISSING, manifest = CHROME_MANIFEST),
                connectorStatus(BrowserConnectorBrowser.FIREFOX, State.STALE),
            ),
        )
        client.failures[BrowserConnectorBrowser.BRAVE] = error
        val result = syncBrowserConnectors(client, removed = { emptySet() })
        assertEquals(setOf(BrowserConnectorBrowser.FIREFOX), result.installed)
        // Each browser of the shared manifest failed.
        assertEquals(
            mapOf(BrowserConnectorBrowser.CHROME to error, BrowserConnectorBrowser.BRAVE to error),
            result.failed,
        )
        assertEquals(listOf("status", "install chrome,brave", "install firefox"), client.calls)
    }

    @Test
    fun `the report is the one of the last call that succeeded`() = runTest {
        val client = FakeBrowserConnectorClient(
            browsers = listOf(
                connectorStatus(BrowserConnectorBrowser.CHROME, State.MISSING),
                connectorStatus(BrowserConnectorBrowser.FIREFOX, State.MISSING),
            ),
        )
        client.failures[BrowserConnectorBrowser.FIREFOX] =
            BrowserConnectorError(BrowserConnectorError.Kind.FAILED)
        val result = syncBrowserConnectors(client, removed = { emptySet() })
        assertEquals(
            listOf(State.INSTALLED, State.MISSING),
            result.report.browsers.map { it.state },
        )
    }

    @Test
    fun `with nothing to do, the report is the status`() = runTest {
        val browsers = listOf(
            connectorStatus(BrowserConnectorBrowser.CHROME, State.INSTALLED),
            connectorStatus(BrowserConnectorBrowser.FIREFOX, State.BROWSER_ABSENT),
        )
        val client = FakeBrowserConnectorClient(browsers)
        val result = syncBrowserConnectors(client, removed = { emptySet() })
        assertEquals(
            BrowserConnectorSync(BrowserConnectorReport(BrowserConnectorPackaging.NATIVE, browsers)),
            result,
        )
        assertEquals(listOf("status"), client.calls)
    }

    @Test
    fun `a report that can't be read fails`() = runTest {
        val client = FakeBrowserConnectorClient(emptyList())
        client.failures[null] = BrowserConnectorError(BrowserConnectorError.Kind.UPDATE_REQUIRED)
        val error = assertFailsWith<BrowserConnectorException> {
            syncBrowserConnectors(client, removed = { emptySet() })
        }
        assertEquals(BrowserConnectorError.Kind.UPDATE_REQUIRED, error.error.kind)
    }
}

private const val CHROME_MANIFEST =
    "/home/user/.config/google-chrome/NativeMessagingHosts/com.artemchep.keyguard.json"

private const val CHROME_REGISTRY_KEY =
    "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\com.artemchep.keyguard"
