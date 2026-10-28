package com.artemchep.keyguard.feature.agent.help

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.agent_auto_setup_host_error
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.plus

@Composable
internal fun produceAgentAutomaticSetupState(
    agent: SetupAgent,
    mode: AgentSetupMode,
): AgentAutomaticSetupState? {
    val runner = rememberAgentSetupRunner(agent)
    val enabled = rememberAgentSetupEnabled(agent)
    return produceScreenState(
        key = "agent_setup:${agent.argument}:${mode.name}",
        args = arrayOf(runner, agent, mode, enabled),
        initial = null,
    ) {
        val client = (runner as? AgentSetupRunner.App)?.client
        val termux = runner is AgentSetupRunner.Termux
        val hostCliResult = (runner as? AgentSetupRunner.HostCli)?.let { hostCli ->
            runCatchingNonFatal { hostCli.resolve() }.onFailure(::recordException)
        }
        val hostCli = hostCliResult?.getOrNull()
        val shells = when {
            termux -> listOf(SetupShell.BASH, SetupShell.ZSH, SetupShell.FISH)
            CurrentPlatform is Platform.Desktop.Windows -> listOf(SetupShell.POWERSHELL, SetupShell.WINDOWS_POWERSHELL)
            else -> SetupShell.entries.filter { it != SetupShell.WINDOWS_POWERSHELL }
        }
        val editor = AgentAutomaticSetupEditor(
            initial = AgentSetupData(
                agent = agent,
                mode = mode,
                termux = termux,
                hostCli = hostCli,
                available = termux || client != null || hostCli != null,
                unavailableError = hostCliResult?.exceptionOrNull()?.toAgentSetupError(),
                shells = shells.toImmutableList(),
                selectedShells = if (termux) persistentSetOf(SetupShell.BASH) else persistentSetOf(),
                step = if (mode == AgentSetupMode.UNDO) AgentSetupStep.REVIEW else AgentSetupStep.SETTINGS,
            ),
            client = client,
            scope = screenScope + Dispatchers.Main.immediate,
            onClose = ::navigatePopSelf,
        )
        enabled.onEach(editor::setEnabled).launchIn(screenScope + Dispatchers.Main.immediate)
        val hostError = translate(Res.string.agent_auto_setup_host_error)
        interceptBackPress(flowOf(editor::back))
        editor.state.map { data ->
            AgentAutomaticSetupState(
                data = data,
                host = TextFieldModel(
                    id = "host",
                    text = data.host,
                    error = hostError.takeIf { data.oneHost && data.host.isNotEmpty() && !data.hostValid },
                    onChange = (editor::setHost).takeIf { data.editable },
                ),
                onShellToggle = editor::toggleShell,
                onHostScopeChange = editor::setOneHost,
                onBack = editor::back,
                onContinue = (editor::next).takeIf { data.canContinue },
                onRefresh = (editor::refresh).takeIf { data.canRefresh },
                onRetry = (editor::verify).takeIf { data.canRetry },
                onDiscard = { editor.confirmDiscard(true) },
                onDismissDiscard = { editor.confirmDiscard(false) },
            )
        }
    }
}
