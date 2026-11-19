package com.artemchep.keyguard.feature.browserextension

import com.artemchep.keyguard.common.service.browser.BrowserApprovalRequest
import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorClient
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorException
import com.artemchep.keyguard.common.service.browser.BrowserConnectorPackaging
import com.artemchep.keyguard.common.service.browser.BrowserConnectorReport
import com.artemchep.keyguard.common.service.browser.BrowserConnectorStatus
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.BrowserExtensionState
import com.artemchep.keyguard.common.service.browser.BrowserSaveRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal fun connectorStatus(
    browser: BrowserConnectorBrowser,
    state: BrowserConnectorStatus.State,
    detected: Boolean = state != BrowserConnectorStatus.State.BROWSER_ABSENT,
    host: String? = "/opt/keyguard/keyguard-browser-host".takeIf {
        state == BrowserConnectorStatus.State.INSTALLED || state == BrowserConnectorStatus.State.STALE
    },
    /** Browsers with the same manifest and registry key share them. */
    manifest: String = "/home/user/.config/${browser.id}/NativeMessagingHosts/com.artemchep.keyguard.json",
    registryKey: String? = null,
) = BrowserConnectorStatus(
    browser = browser,
    state = state,
    detected = detected,
    manifest = manifest,
    registryKey = registryKey,
    host = host,
)

/**
 * A connector installer that keeps the state of each browser: install
 * makes it installed, uninstall makes it missing or absent.
 */
internal class FakeBrowserConnectorClient(
    browsers: List<BrowserConnectorStatus>,
    private val packaging: BrowserConnectorPackaging = BrowserConnectorPackaging.NATIVE,
) : BrowserConnectorClient {
    val browsers = browsers.toMutableList()
    val calls = mutableListOf<String>()

    /** Fails the next operations of these browsers, or every operation for `null`. */
    val failures = mutableMapOf<BrowserConnectorBrowser?, BrowserConnectorError>()
    var gate: CompletableDeferred<Unit>? = null

    /**
     * Another operation that holds the CLI once the report is read: an
     * [installIf] waits for it, then reads its condition.
     */
    var busy: CompletableDeferred<Unit>? = null

    override suspend fun status(): BrowserConnectorReport {
        calls += "status"
        return run(emptySet()) { it }
    }

    override suspend fun install(
        browsers: Set<BrowserConnectorBrowser>,
    ): BrowserConnectorReport {
        calls += "install " + browsers.joinToString(",") { it.id }
        return run(browsers) { status ->
            status.copy(
                state = BrowserConnectorStatus.State.INSTALLED,
                host = "/opt/keyguard/keyguard-browser-host",
            )
        }
    }

    override suspend fun installIf(
        browsers: Set<BrowserConnectorBrowser>,
        proceed: () -> Boolean,
    ): BrowserConnectorReport? {
        busy?.await()
        return if (proceed()) install(browsers) else null
    }

    override suspend fun uninstall(
        browsers: Set<BrowserConnectorBrowser>,
    ): BrowserConnectorReport {
        calls += "uninstall " + browsers.joinToString(",") { it.id }
        return run(browsers) { status ->
            status.copy(
                state = if (status.detected) {
                    BrowserConnectorStatus.State.MISSING
                } else {
                    BrowserConnectorStatus.State.BROWSER_ABSENT
                },
                host = null,
            )
        }
    }

    private suspend fun run(
        selected: Set<BrowserConnectorBrowser>,
        change: (BrowserConnectorStatus) -> BrowserConnectorStatus,
    ): BrowserConnectorReport {
        gate?.await()
        val failure = failures[null] ?: selected.firstNotNullOfOrNull { failures[it] }
        if (failure != null) throw BrowserConnectorException(failure)
        browsers.replaceAll { status ->
            if (status.browser in selected) change(status) else status
        }
        return BrowserConnectorReport(packaging, browsers.toList())
    }
}

internal class FakeBrowserExtensionService(
    initial: BrowserExtensionState = BrowserExtensionState(),
) : BrowserExtensionService {
    override val state = MutableStateFlow(initial)
    override val approvals: StateFlow<List<BrowserApprovalRequest>> = MutableStateFlow(emptyList())
    override val saves: StateFlow<List<BrowserSaveRequest>> = MutableStateFlow(emptyList())
    var failure: Exception? = null

    /** Holds the changes of the pairings until completed, like a busy store. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun setEnabled(enabled: Boolean) {
        state.update { it.copy(enabled = enabled) }
    }

    override fun respondToApproval(id: String, approved: Boolean) = false

    override fun namePairing(id: String, name: String) = false

    override fun setAddUri(id: String, add: Boolean) = false

    override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean) = false

    override fun onSaveShown(id: String) = Unit

    override fun closeSave(id: String) = Unit

    override suspend fun removePairing(id: String) {
        gate?.await()
        failure?.let { throw it }
        state.update { state ->
            state.copy(
                pairings = state.pairings.filterNot { it.id == id },
                // Its sessions learn it and end.
                sessions = state.sessions.filterNot { it.pairingId == id },
            )
        }
    }

    override suspend fun renamePairing(id: String, name: String) {
        gate?.await()
        failure?.let { throw it }
        state.update { state ->
            state.copy(
                pairings = state.pairings.map { if (it.id == id) it.copy(name = name) else it },
            )
        }
    }

    override suspend fun setConnectorsRemoved(browsers: Set<BrowserConnectorBrowser>, removed: Boolean) {
        state.update { state ->
            state.copy(
                removedConnectors = if (removed) {
                    state.removedConnectors + browsers
                } else {
                    state.removedConnectors - browsers
                },
            )
        }
    }
}
