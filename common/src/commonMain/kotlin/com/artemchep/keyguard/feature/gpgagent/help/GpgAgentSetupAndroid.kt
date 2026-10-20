package com.artemchep.keyguard.feature.gpgagent.help

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.agent.help.AgentSetupCodeBlock
import com.artemchep.keyguard.feature.agent.help.AgentSetupParagraph
import com.artemchep.keyguard.feature.agent.help.AgentSetupTermuxInstall
import com.artemchep.keyguard.feature.home.vault.component.LargeSection
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource

private const val GPG_AGENT_TERMUX_PKG_INSTALL = """pkg install gnupg keyguard-android-gpg-agent"""

// Prompts before rebuilding ~/storage, so it must not share a block with the import.
private const val GPG_AGENT_TERMUX_STORAGE_SETUP = "termux-setup-storage"

private const val GPG_AGENT_TERMUX_IMPORT_CMD =
    "gpg --import ~/storage/downloads/gpg_*.public.asc"

private const val GPG_AGENT_TERMUX_PKG_SETUP =
    $$"""if [ -x "$PREFIX/bin/keyguard-android-gpg-agent" ]; then
  eval "$("$PREFIX/bin/keyguard-android-gpg-agent")"
fi"""

@Composable
internal fun ColumnScope.GpgAgentSetupAndroidPlatformContent() {
    LargeSection(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_android_termux_text),
    )
    AgentSetupTermuxInstall(
        installCommand = GPG_AGENT_TERMUX_PKG_INSTALL,
    )
    GpgAgentSetupAndroidStep(
        title = stringResource(Res.string.gpg_agent_setup_android_termux_step_3_title),
        texts = listOf(
            stringResource(Res.string.gpg_agent_setup_android_termux_step_3_shell_current),
            stringResource(Res.string.ssh_agent_setup_android_termux_step_3_shell_startup),
        ),
        commands = listOf(GPG_AGENT_TERMUX_PKG_SETUP),
    )
    GpgAgentSetupAndroidStep(
        title = stringResource(Res.string.gpg_agent_setup_android_termux_import_title),
        texts = listOf(stringResource(Res.string.gpg_agent_setup_android_termux_import_text)),
        commands = listOf(
            GPG_AGENT_TERMUX_STORAGE_SETUP,
            GPG_AGENT_TERMUX_IMPORT_CMD,
        ),
    )
    GpgAgentSetupAndroidStep(
        title = stringResource(Res.string.ssh_agent_setup_android_termux_step_4_title),
        texts = listOf(stringResource(Res.string.gpg_agent_setup_step_4_text)),
        commands = listOf(
            GPG_AGENT_SETUP_LIST_KEYS_CMD,
            GPG_AGENT_SETUP_VERIFY_CMD_SIGN,
        ),
    )
    GpgAgentSetupAndroidStep(
        title = stringResource(Res.string.gpg_agent_setup_android_termux_git_title),
        texts = listOf(stringResource(Res.string.gpg_agent_setup_step_5_text)),
        commands = listOf(GPG_AGENT_SETUP_GIT_CONFIG_CMD),
    )
    LargeSection(
        text = stringResource(Res.string.ssh_agent_setup_android_how_does_it_work_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_android_how_does_it_work_1),
    )
    Spacer(
        modifier = Modifier
            .height(8.dp),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_android_how_does_it_work_2),
    )
}

@Composable
private fun ColumnScope.GpgAgentSetupAndroidStep(
    title: String,
    texts: List<String>,
    commands: List<String>,
) {
    Section(
        text = title,
    )
    texts.forEach { text ->
        AgentSetupParagraph(
            text = text,
        )
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
    }
    commands.forEach { command ->
        AgentSetupCodeBlock(
            text = command,
        )
    }
}
