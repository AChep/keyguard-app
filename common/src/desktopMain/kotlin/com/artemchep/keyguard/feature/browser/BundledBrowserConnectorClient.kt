package com.artemchep.keyguard.feature.browser

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.agent.findAgentBinary
import com.artemchep.keyguard.common.service.agent.runAgentCommand
import com.artemchep.keyguard.common.service.browser.BrowserConnectorBrowser
import com.artemchep.keyguard.common.service.browser.BrowserConnectorClient
import com.artemchep.keyguard.common.service.browser.BrowserConnectorError
import com.artemchep.keyguard.common.service.browser.BrowserConnectorException
import com.artemchep.keyguard.common.service.browser.BrowserConnectorPackaging
import com.artemchep.keyguard.common.service.browser.BrowserConnectorReport
import com.artemchep.keyguard.common.service.browser.BrowserConnectorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * Runs `keyguard browsers` of the bundled CLI. Inside Flatpak the CLI runs in
 * the sandbox, which shares the browsers' manifest directories.
 *
 * With [dev], for debug builds, the CLI manages the DEV registration,
 * `com.artemchep.keyguard.dev`, which only the development build of the
 * extension calls. Release builds manage `com.artemchep.keyguard`, so the
 * two builds never write each other's manifests.
 *
 * @param cli The CLI, `null` if this build has none.
 */
class BundledBrowserConnectorClient(
    private val dev: Boolean = false,
    private val cli: () -> Path? = { findAgentBinary("keyguard") },
) : BrowserConnectorClient {
    // One CLI run at a time: the settings screen and the desktop
    // integration may sync at once, and would write the same manifests.
    private val mutex = Mutex()

    override suspend fun status(): BrowserConnectorReport = mutex.withLock {
        // Interruptible: cancelling the operation stops the CLI, which
        // would otherwise hold the lock until it finishes. Status only reads.
        runInterruptible(Dispatchers.IO) { runCli(listOf("status")) }
    }

    override suspend fun install(
        browsers: Set<BrowserConnectorBrowser>,
    ): BrowserConnectorReport = mutex.withLock { change(installArguments(browsers)) }

    override suspend fun installIf(
        browsers: Set<BrowserConnectorBrowser>,
        proceed: () -> Boolean,
    ): BrowserConnectorReport? = mutex.withLock {
        // Under the lock: the user may have changed their choice while
        // another run held it.
        if (proceed()) change(installArguments(browsers)) else null
    }

    override suspend fun uninstall(
        browsers: Set<BrowserConnectorBrowser>,
    ): BrowserConnectorReport = mutex.withLock {
        change(listOf("uninstall") + browserArguments(browsers))
    }

    private fun installArguments(browsers: Set<BrowserConnectorBrowser>) =
        listOf("install") + browserArguments(browsers)

    private fun browserArguments(browsers: Set<BrowserConnectorBrowser>) =
        browsers.flatMap { listOf("--browser", it.id) }

    /**
     * Runs a command that writes the manifests. Not interruptible: once
     * started, it runs to its end or to the timeout. Stopped midway, it would
     * leave temporary files and, for the AppImage, a partial copy of the
     * connector.
     */
    private suspend fun change(arguments: List<String>): BrowserConnectorReport =
        withContext(Dispatchers.IO) { runCli(arguments) }

    /** Runs the CLI with [arguments] and reads its report; blocks until it ends. */
    private fun runCli(arguments: List<String>): BrowserConnectorReport {
        val cli = cli()
            ?: throw BrowserConnectorException(
                BrowserConnectorError(BrowserConnectorError.Kind.UNSUPPORTED, detail = "The CLI is missing"),
            )
        // The CLI reports failures as JSON codes, which the app translates.
        val command = listOf(cli.toString(), "browsers", "--json") +
            listOfNotNull("--dev".takeIf { dev }) +
            arguments
        val result = runAgentCommand(command, BROWSER_CONNECTOR_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            throw BrowserConnectorException(parseBrowserConnectorError(result.output))
        }
        return parseBrowserConnectorReport(result.output)
    }
}

private val reportJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ReportDto(
    val packaging: String,
    val browsers: List<BrowserDto>,
)

@Serializable
private data class BrowserDto(
    val browser: String,
    val status: String,
    val detected: Boolean,
    val manifest: String,
    @SerialName("registry_key")
    val registryKey: String? = null,
    val host: String? = null,
)

@Serializable
private data class ErrorDto(
    val code: String,
    val path: String? = null,
    val browser: String? = null,
    val alias: String? = null,
    val detail: String? = null,
)

internal fun parseBrowserConnectorReport(raw: String): BrowserConnectorReport {
    val element = reportJson.parseToJsonElement(reportOf(raw))
    val version = element.jsonObject["version"]?.jsonPrimitive?.intOrNull
    if (version != BROWSER_CONNECTOR_REPORT_VERSION) {
        updateRequired("Unsupported browser connector report version $version")
    }
    val report = reportJson.decodeFromJsonElement<ReportDto>(element)
    return BrowserConnectorReport(
        packaging = BrowserConnectorPackaging.entries.firstOrNull { it.id == report.packaging }
            ?: updateRequired("Unsupported packaging ${report.packaging}"),
        browsers = report.browsers.map { browser ->
            BrowserConnectorStatus(
                browser = browserOf(browser.browser)
                    ?: updateRequired("Unsupported browser ${browser.browser}"),
                state = BrowserConnectorStatus.State.entries.firstOrNull { it.code == browser.status }
                    ?: updateRequired("Unsupported connector status ${browser.status}"),
                detected = browser.detected,
                manifest = browser.manifest,
                registryKey = browser.registryKey,
                host = browser.host,
            )
        },
    )
}

/**
 * The report in the output, which has the standard error too: e.g. warnings
 * of the dynamic linker before or after it. The CLI pretty-prints the report:
 * it starts with a line `{` and ends with a line `}`, both unindented.
 */
private fun reportOf(raw: String): String {
    val lines = raw.lines()
    val start = lines.indexOfFirst { it.trimEnd() == "{" }
    if (start < 0) return raw
    val end = (start until lines.size).firstOrNull { lines[it].trimEnd() == "}" }
        ?: lines.lastIndex
    return lines.subList(start, end + 1).joinToString(separator = "\n")
}

/**
 * Reads the CLI's JSON error, a line of its own among the warnings of the
 * standard error; any other output is the detail of a generic failure.
 */
internal fun parseBrowserConnectorError(raw: String): BrowserConnectorError {
    val text = raw.trim()
    val error = text.lines()
        .asReversed()
        .filter { it.startsWith("{") }
        .firstNotNullOfOrNull { line ->
            runCatchingNonFatal { reportJson.decodeFromString<ErrorDto>(line) }.getOrNull()
        }
    val kind = BrowserConnectorError.Kind.entries.firstOrNull { it.code == error?.code }
    return if (error != null && kind != null) {
        BrowserConnectorError(
            kind = kind,
            path = error.path,
            browser = error.browser?.let(::browserOf),
            alias = error.alias,
            detail = error.detail,
        )
    } else {
        BrowserConnectorError(BrowserConnectorError.Kind.FAILED, detail = text.ifEmpty { null })
    }
}

private fun browserOf(id: String) = BrowserConnectorBrowser.entries.firstOrNull { it.id == id }

private fun updateRequired(detail: String): Nothing = throw BrowserConnectorException(
    BrowserConnectorError(BrowserConnectorError.Kind.UPDATE_REQUIRED, detail = detail),
)

private const val BROWSER_CONNECTOR_REPORT_VERSION = 1

/** Status reads a few small files; install may copy the connector for the AppImage. */
private const val BROWSER_CONNECTOR_TIMEOUT_SECONDS = 30L
