package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.browser.BrowserConnectorClient
import com.artemchep.keyguard.common.service.browser.BrowserExtensionService
import com.artemchep.keyguard.common.service.browser.toBrowserConnectorError
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import com.artemchep.keyguard.common.service.cli.agent.CliAgentManager
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.feature.browserextension.syncBrowserConnectors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

private const val TAG = "BrowserExtension"

/**
 * Serves browser extensions in this build: their connector reaches the app
 * through the CLI agent, which [CliAgentIntegration] runs.
 */
@Composable
internal fun BrowserExtensionIntegration() {
    val host: CliAgentHost = koinInject()
    val manager: CliAgentManager = koinInject()
    val connectors: BrowserConnectorClient = koinInject()
    val logRepository: LogRepository = koinInject()
    LaunchedEffect(manager) {
        host.browser.configureRuntime(supported = manager.defaultBinaryPath != null)
    }
    LaunchedEffect(host, connectors) {
        // At startup and each time the integration turns on: a browser
        // may be new, the app may have moved, or an update replaced the
        // AppImage. Turning it on again restarts a running sync, which
        // skipped the browsers while it was off; a started install ends first.
        host.browser.state
            .map { it.needsAgent }
            .distinctUntilChanged()
            .filter { it }
            .collectLatest {
                syncConnectors(connectors, host.browser, logRepository)
            }
    }
}

/**
 * Installs the missing connectors. Stops if the integration turns off,
 * and skips a connector the user removes meanwhile.
 */
private suspend fun syncConnectors(
    connectors: BrowserConnectorClient,
    service: BrowserExtensionService,
    logRepository: LogRepository,
) {
    val result = runCatchingNonFatal {
        syncBrowserConnectors(connectors, service)
    }.getOrElse { e ->
        val error = e.toBrowserConnectorError()
        logRepository.post(
            TAG,
            "Failed to check the browser connectors: ${error.kind.code} ${error.detail}",
            LogLevel.WARNING,
        )
        return
    }
    if (result.installed.isNotEmpty()) {
        val browsers = result.installed.joinToString { it.id }
        logRepository.post(TAG, "Installed the browser connectors of $browsers", LogLevel.INFO)
    }
    result.failed.forEach { (browser, error) ->
        logRepository.post(
            TAG,
            "Failed to install the browser connector of ${browser.id}: ${error.kind.code} ${error.detail}",
            LogLevel.WARNING,
        )
    }
}
