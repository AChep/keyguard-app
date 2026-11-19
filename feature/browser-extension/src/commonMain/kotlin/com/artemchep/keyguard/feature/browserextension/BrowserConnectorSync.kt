package com.artemchep.keyguard.feature.browserextension

import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorClient
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorReport
import com.artemchep.keyguard.common.service.browser.BrowserConnectorStatus
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.groups
import com.artemchep.keyguard.common.service.browser.toBrowserConnectorError
import kotlinx.coroutines.CancellationException

/** What [syncBrowserConnectors] did. */
data class BrowserConnectorSync(
    /** The report of the last call that succeeded. */
    val report: BrowserConnectorReport,
    /** The browsers whose connectors it installed or repaired. */
    val installed: Set<BrowserConnectorBrowser> = emptySet(),
    val failed: Map<BrowserConnectorBrowser, BrowserConnectorError> = emptyMap(),
)

/**
 * Installs and repairs the connectors of the browsers found, e.g. after the
 * app moved or the AppImage updated, while the integration of [service] is
 * on. Leaves the browsers whose connectors the user removed alone.
 *
 * @throws Exception If the report can't be read.
 */
suspend fun syncBrowserConnectors(
    client: BrowserConnectorClient,
    service: BrowserExtensionService,
): BrowserConnectorSync = syncBrowserConnectors(
    client = client,
    removed = { service.state.value.removedConnectors },
    enabled = { service.state.value.needsAgent },
)

/**
 * Installs and repairs the connectors of the browsers found, e.g. after the
 * app moved or the AppImage updated. Leaves the [removed] browsers alone, with
 * every browser that shares their manifest, and the manifests that Keyguard
 * didn't write.
 *
 * @param removed The browsers whose connectors the user removed. Read right
 * before each install starts: the user may remove one while the sync runs.
 * @param enabled Whether to go on installing, e.g. while the integration
 * is on. Read right before each install starts.
 * @throws Exception If the report can't be read.
 */
suspend fun syncBrowserConnectors(
    client: BrowserConnectorClient,
    removed: () -> Set<BrowserConnectorBrowser>,
    enabled: () -> Boolean = { true },
): BrowserConnectorSync {
    var report = client.status()
    // Each group with the browsers to install.
    val pending = report.browsers.groups()
        .mapNotNull { group ->
            group.browsersToInstall()
                ?.let { browsers -> group.map { it.browser } to browsers }
        }
    val installed = mutableSetOf<BrowserConnectorBrowser>()
    val failed = mutableMapOf<BrowserConnectorBrowser, BrowserConnectorError>()
    // One manifest at a time, so that a file Keyguard refuses to
    // change for one browser doesn't keep the others broken.
    pending.forEach { (group, browsers) ->
        // The user may have removed a connector or turned the integration
        // off since the report, or while another operation held the CLI.
        val proceed = { enabled() && removed().none { it in group } }
        @Suppress("TooGenericExceptionCaught") // A failure of one browser is reported, not thrown.
        try {
            val newReport = client.installIf(browsers, proceed)
            if (newReport != null) {
                report = newReport
                installed += browsers
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.toBrowserConnectorError()
            browsers.forEach { failed[it] = error }
        }
    }
    return BrowserConnectorSync(
        report = report,
        installed = installed,
        failed = failed,
    )
}

/**
 * The detected browsers of a group that share one manifest, if the manifest
 * needs a repair, or is missing and a browser of the group was found.
 */
private fun List<BrowserConnectorStatus>.browsersToInstall(): Set<BrowserConnectorBrowser>? {
    val detected = filter { it.detected }.mapTo(mutableSetOf()) { it.browser }
    val stale = any { status ->
        status.state == BrowserConnectorStatus.State.STALE &&
            // Written by Keyguard, for a browser that is still there.
            status.host != null &&
            status.detected
    }
    val missing = detected.isNotEmpty() && all { status ->
        status.state == BrowserConnectorStatus.State.MISSING ||
            status.state == BrowserConnectorStatus.State.BROWSER_ABSENT
    }
    return detected.takeIf { stale || missing }
}
