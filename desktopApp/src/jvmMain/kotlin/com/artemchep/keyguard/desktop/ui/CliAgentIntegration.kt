package com.artemchep.keyguard.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.artemchep.keyguard.common.service.cli.CliPlatformCapability
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import com.artemchep.keyguard.common.service.cli.agent.CliAgentManager
import com.artemchep.keyguard.common.service.cli.agent.supervise
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.feature.cli.cliMcpArguments
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.protocol_cli
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Configures the CLI and the environments integration of this build, and runs their agent. */
@Composable
internal fun CliAgentIntegration() {
    val host: CliAgentHost = koinInject()
    val manager: CliAgentManager = koinInject()
    val showMessage: ShowMessage = koinInject()
    LaunchedEffect(manager) {
        val command = manager.cliBinaryPath?.toAbsolutePath()?.toString() ?: "keyguard"
        val flatpak = (CurrentPlatform as? Platform.Desktop.Linux)?.isFlatpak == true
        host.mcp.configure(
            command = if (flatpak) "flatpak" else command,
            arguments = environmentMcpArguments(command, flatpak, manager.defaultAgentSocketPath),
        )
        host.mounts.configure(
            supported = CurrentPlatform is Platform.Desktop.Linux || CurrentPlatform is Platform.Desktop.MacOS,
        )
        host.cli.configureRuntime(
            path = manager.cliBinaryPath?.toAbsolutePath()?.toString(),
            capabilities = cliCapabilities(flatpak),
            socketPath = manager.defaultAgentSocketPath?.toAbsolutePath()?.toString(),
        )
    }
    LaunchedEffect(manager) {
        val translatorScope = TranslatorScope.of(LeContext())
        withContext(Dispatchers.IO) {
            host.supervise(
                available = manager.defaultBinaryPath != null,
                onFailure = { failure ->
                    showAgentFailure(failure, Res.string.protocol_cli, translatorScope, showMessage)
                },
                start = { scope -> manager.start(scope) },
                join = { process ->
                    process.onExit().await()
                    "CLI agent process exited unexpectedly: ${process.exitValue()}"
                },
                stop = { manager.stop() },
            )
        }
    }
}

/** The Flatpak sandbox holds the CLI, and every command that the CLI starts. */
internal fun cliCapabilities(flatpak: Boolean): Set<CliPlatformCapability> =
    if (flatpak) emptySet() else setOf(CliPlatformCapability.Unsandboxed)

internal fun environmentMcpArguments(command: String, flatpak: Boolean, socketPath: Path?): List<String> = buildList {
    if (flatpak) {
        addAll(listOf("run", "--command=$command", "com.artemchep.keyguard"))
    }
    addAll(cliMcpArguments(socketPath?.toAbsolutePath()?.toString()))
}
