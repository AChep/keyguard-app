package com.artemchep.keyguard.feature.browserextension.ui

import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorClient
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorReport
import com.artemchep.keyguard.common.service.browser.BrowserConnectorStatus
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.BrowserExtensionState
import com.artemchep.keyguard.common.service.browser.groups
import com.artemchep.keyguard.common.service.browser.toBrowserConnectorError
import com.artemchep.keyguard.feature.browserextension.BrowserConnectorSync
import com.artemchep.keyguard.feature.browserextension.syncBrowserConnectors
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState.ConnectorStatus
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState.SetupConnector
import com.artemchep.keyguard.presentation.operation.SingleOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private typealias ConnectorAction = suspend (BrowserConnectorClient) -> BrowserConnectorSync

/** Why the last operation on the connectors failed. */
private class ConnectorFailure(
    val error: BrowserConnectorError,
    /** Repeats the operation that failed. */
    val retry: () -> Unit,
)

/** Installs the connectors of a group, or removes them. */
private typealias ConnectorChange = (
    browsers: Set<BrowserConnectorBrowser>,
    detected: Set<BrowserConnectorBrowser>,
    install: Boolean,
) -> Unit

/**
 * Produces the connectors and the paired browsers of the browser extension
 * settings. Once collected, and each time the integration turns on, it
 * installs and repairs the connectors like the app does; while the
 * integration is off it only reads their report. One operation on the
 * connectors and one on the pairings run at a time; stopping the
 * collection cancels them, except a started change of a connector or
 * removal of a pairing.
 *
 * @param connectors `null` if this build installs no connectors.
 */
fun browserExtensionSettingsStateProducer(
    service: BrowserExtensionService,
    connectors: BrowserConnectorClient?,
): Flow<BrowserExtensionSettingsState> = channelFlow {
    val connectorOperation = SingleOperation(this)
    val pairingOperation = SingleOperation(this)
    val report = MutableStateFlow<BrowserConnectorReport?>(null)
    val connectorFailure = MutableStateFlow<ConnectorFailure?>(null)

    fun onConnectors(action: ConnectorAction) {
        val client = connectors
            ?: return
        connectorOperation.launch {
            connectorFailure.value = null
            val retry = { onConnectors(action) }
            @Suppress("TooGenericExceptionCaught") // Shows why it failed, then fails the operation.
            try {
                val result = action(client)
                report.value = result.report
                // A sync goes on after a browser fails.
                connectorFailure.value = result.failed.values.firstOrNull()
                    ?.let { error -> ConnectorFailure(error, retry) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectorFailure.value = ConnectorFailure(e.toBrowserConnectorError(), retry)
                throw e
            }
        }
    }

    // Installs the missing connectors while the integration is on,
    // otherwise only reads the report.
    fun onSync() = onConnectors { client ->
        if (service.state.value.needsAgent) {
            syncBrowserConnectors(client, service)
        } else {
            BrowserConnectorSync(report = client.status())
        }
    }

    fun onConnectorChange(
        browsers: Set<BrowserConnectorBrowser>,
        detected: Set<BrowserConnectorBrowser>,
        install: Boolean,
    ) = onConnectors { client ->
        // Not cancelled once started: leaving the screen between the
        // record and the CLI would keep a removed connector installed,
        // which the syncs then skip.
        withContext(NonCancellable) {
            // Recorded first, so the choice holds even if the change fails.
            service.setConnectorsRemoved(browsers, removed = !install)
            val newReport = if (install) {
                client.install(detected)
            } else {
                client.uninstall(browsers)
            }
            BrowserConnectorSync(report = newReport)
        }
    }

    fun onPairing(action: suspend () -> Unit) {
        pairingOperation.launch(block = action)
    }

    if (connectors != null) {
        launch {
            service.syncs().collect {
                // After a running switch, which would refuse the sync.
                connectorOperation.state.first { operation -> !operation.busy }
                onSync()
            }
        }
    }

    combine(
        service.state,
        report,
        connectorFailure,
        connectorOperation.state,
        pairingOperation.state,
    ) { state, currentReport, currentConnectorFailure, connectorOperationState, pairingOperationState ->
        val connectorsBusy = connectorOperationState.busy
        BrowserExtensionSettingsState(
            status = state.toStatus(connectorFailed = currentConnectorFailure != null),
            setup = state.toSetup(
                report = currentReport,
                connector = connectors?.let {
                    currentReport.toSetupConnector(
                        busy = connectorsBusy,
                        failure = currentConnectorFailure,
                        onSync = ::onSync,
                    )
                },
            ),
            connectors = connectors?.let {
                currentReport.toConnectors(
                    busy = connectorsBusy,
                    failure = currentConnectorFailure,
                    onChange = ::onConnectorChange,
                )
            },
            pairings = state.toPairings(
                idle = !pairingOperationState.busy,
                onPairing = ::onPairing,
                service = service,
            ),
            pairingFailed = pairingOperationState.failed,
        )
    }.collect(::send)
}

/** Emits once collected, then each time the integration turns on. */
private fun BrowserExtensionService.syncs(): Flow<Unit> = state
    .map { it.needsAgent }
    .distinctUntilChanged()
    .withIndex()
    .filter { (index, needsAgent) -> needsAgent || index == 0 }
    .map { }

private fun BrowserExtensionState.toStatus(
    connectorFailed: Boolean,
): BrowserExtensionSettingsState.Status? = when {
    !enabled -> null
    connectorFailed -> BrowserExtensionSettingsState.Status.ConnectorFailed
    pairings.isNotEmpty() -> BrowserExtensionSettingsState.Status.Paired(
        paired = pairings.size,
        connected = pairings.count { pairing ->
            sessions.any { it.pairingId == pairing.id }
        },
    )

    else -> null
}

private fun BrowserConnectorReport?.toConnectors(
    busy: Boolean,
    failure: ConnectorFailure?,
    onChange: ConnectorChange,
) = BrowserExtensionSettingsState.Connectors(
    packaging = this?.packaging,
    items = this?.browsers.orEmpty().groups().mapNotNull { group ->
        group.toConnector(busy = busy, onChange = onChange)
    },
    busy = busy,
    error = failure?.error,
    onRetry = failure?.retry?.takeIf { !busy },
)

/** The status of the browsers that share one manifest. */
private fun List<BrowserConnectorStatus>.connectorStatus(): ConnectorStatus = when {
    any { it.state == BrowserConnectorStatus.State.STALE } -> ConnectorStatus.NEEDS_REPAIR
    any { it.state == BrowserConnectorStatus.State.INSTALLED } -> ConnectorStatus.INSTALLED
    else -> ConnectorStatus.NOT_INSTALLED
}

/** The browsers found of a group, or all of them if none was found. */
private fun List<BrowserConnectorStatus>.shownBrowsers(): List<BrowserConnectorBrowser> =
    filter { it.detected }
        .ifEmpty { this }
        .map { it.browser }

/** One row of the browsers that share one manifest, `null` if none of them is there. */
private fun List<BrowserConnectorStatus>.toConnector(
    busy: Boolean,
    onChange: ConnectorChange,
): BrowserExtensionSettingsState.Connector? {
    if (all { it.state == BrowserConnectorStatus.State.BROWSER_ABSENT }) {
        return null
    }
    val browsers = mapTo(mutableSetOf()) { it.browser }
    val detected = filter { it.detected }.mapTo(mutableSetOf()) { it.browser }
    val status = connectorStatus()
    val checked = status != ConnectorStatus.NOT_INSTALLED
    val onCheckedChange = { install: Boolean ->
        // Installing for a browser that is gone fails.
        if (!install || detected.isNotEmpty()) {
            onChange(browsers, detected, install)
        }
    }
    return BrowserExtensionSettingsState.Connector(
        id = joinToString(separator = ",") { it.browser.id },
        browsers = shownBrowsers(),
        status = status,
        checked = checked,
        onCheckedChange = onCheckedChange
            .takeIf { !busy && (checked || detected.isNotEmpty()) },
    )
}

/** The setup guide, until a browser is paired while the integration is on. */
private fun BrowserExtensionState.toSetup(
    report: BrowserConnectorReport?,
    connector: SetupConnector?,
) = BrowserExtensionSettingsState.Setup(
    connector = connector,
    stores = report.stores(),
    extensionConnected = sessions.isNotEmpty(),
).takeIf { needsAgent && pairings.isEmpty() }

/** @param onSync Looks for the browsers again. */
private fun BrowserConnectorReport?.toSetupConnector(
    busy: Boolean,
    failure: ConnectorFailure?,
    onSync: () -> Unit,
): SetupConnector {
    val failed = failure?.let { SetupConnector.Failed(it.error, it.retry) }
    if (this == null) {
        return failed?.takeIf { !busy }
            ?: SetupConnector.Busy
    }
    // Only the browsers found: a manifest left behind by a browser
    // that is gone serves no browser.
    val installed = browsers.groups()
        .filter { group -> group.connectorStatus() == ConnectorStatus.INSTALLED }
        .flatMap { group -> group.filter { it.detected }.map { it.browser } }
    // A browser found whose manifest Keyguard didn't write. The syncs
    // skip that manifest: it stays until the user removes it.
    val foreign = browsers.groups()
        .filter { group -> group.any { it.state == BrowserConnectorStatus.State.STALE && it.host == null } }
        .flatten()
        .firstOrNull { it.detected }
    return when {
        installed.isNotEmpty() -> SetupConnector.Installed(installed)
        busy -> SetupConnector.Busy
        failed != null -> failed
        browsers.none { it.detected } -> SetupConnector.NoBrowser(onSync)
        foreign != null -> SetupConnector.Failed(
            error = BrowserConnectorError(
                kind = BrowserConnectorError.Kind.FOREIGN_FILE,
                path = foreign.manifest,
                browser = foreign.browser,
            ),
            onRetry = onSync,
        )

        else -> SetupConnector.TurnOnBrowser
    }
}

/** The stores of the browsers found, or every store if none was found. */
private fun BrowserConnectorReport?.stores(): List<BrowserExtensionStore> {
    val found = this?.browsers.orEmpty()
        .filter { it.detected }
        .mapTo(mutableSetOf()) { it.browser.store }
    return BrowserExtensionStore.entries
        .filter { store -> found.isEmpty() || store in found }
}

private fun BrowserExtensionState.toPairings(
    idle: Boolean,
    onPairing: (suspend () -> Unit) -> Unit,
    service: BrowserExtensionService,
) = pairings.map { pairing ->
    val onRename = { name: String ->
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            onPairing { service.renamePairing(pairing.id, trimmed) }
        }
    }
    val onRemove = {
        onPairing {
            // Not cancelled once started: leaving the screen would keep
            // the browser paired, with no error.
            withContext(NonCancellable) { service.removePairing(pairing.id) }
        }
    }
    BrowserExtensionSettingsState.Pairing(
        id = pairing.id,
        name = pairing.name,
        origin = pairing.origin,
        createdAt = pairing.createdAt,
        lastUsedAt = pairing.lastUsedAt,
        connected = sessions.any { it.pairingId == pairing.id },
        onRename = onRename.takeIf { idle },
        onRemove = onRemove.takeIf { idle },
    )
}
