package com.artemchep.keyguard.feature.gpgagent.help

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.feature.agent.help.AgentSetupBodyLabel
import com.artemchep.keyguard.feature.agent.help.AgentSetupCodeBlock
import com.artemchep.keyguard.feature.agent.help.AgentSetupParagraph
import com.artemchep.keyguard.feature.agent.help.AgentSetupScaffold
import com.artemchep.keyguard.feature.agent.help.AgentSetupSectionDivider
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.stringResource

private const val BUILD_TYPE_DEV = "DEV"
private const val GPG_AGENT_SETUP_MACOS_DEV_HOME =
    $$"/tmp/keyguard-$(id -u)/gnupg"
private const val GPG_AGENT_SETUP_MACOS_RELEASE_HOME =
    $$"${HOME}/Library/Group Containers/com.artemchep.keyguard/gnupg"
private const val GPG_AGENT_SETUP_LINUX_HOME =
    $$"${XDG_RUNTIME_DIR}/keyguard-gpg-agent"
private const val GPG_AGENT_SETUP_LINUX_FLATPAK_HOME =
    $$"${HOME}/.var/app/com.artemchep.keyguard/data/gnupg"
private const val GPG_AGENT_SETUP_LINUX_HOME_FALLBACK =
    $$"/tmp/keyguard-$(id -u)/gnupg"
private const val GPG_AGENT_SETUP_WINDOWS_PIPE =
    "\\\\.\\pipe\\keyguard-gpg-agent"
private const val GPG_AGENT_SETUP_IMPORT_CMD =
    "gpg --import /path/to/keyguard-public-key.asc"
private const val GPG_AGENT_SETUP_LIST_KEYS_CMD =
    "gpg --no-autostart --list-secret-keys --with-keygrip --keyid-format=long"
private const val GPG_AGENT_SETUP_VERIFY_CMD_LIST =
    $$"""GPG_AGENT_SOCKET="$(gpgconf --homedir "$GNUPGHOME" --list-dirs agent-socket)"
gpg-connect-agent --raw-socket "$GPG_AGENT_SOCKET" "KEYINFO --list" /bye"""
private const val GPG_AGENT_SETUP_WINDOWS_VERIFY_CMD_LIST =
    "gpg-connect-agent --raw-socket \"\\\\.\\pipe\\keyguard-gpg-agent\" \"KEYINFO --list\" /bye"
private const val GPG_AGENT_SETUP_VERIFY_CMD_SIGN =
    $$"""printf "Keyguard GPG agent test\n" | gpg --no-autostart --local-user YOUR_KEY_FINGERPRINT --clearsign"""
private const val GPG_AGENT_SETUP_GIT_CONFIG_CMD = """git config --local user.signingkey YOUR_KEY_FINGERPRINT
git config --local commit.gpgsign true
git config --local gpg.format openpgp
git config --local gpg.program gpg"""

@Composable
fun GpgAgentSetupScreen() {
    AgentSetupScaffold(
        title = stringResource(Res.string.gpg_agent_setup_header_title),
    ) {
        GpgAgentSetupScreenContent()
    }
}

@Composable
private fun ColumnScope.GpgAgentSetupScreenContent() {
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_intro),
    )

    when (val platform = CurrentPlatform) {
        is Platform.Desktop.MacOS -> GpgAgentSetupSupportedPlatformContent(
            gpgHome = gpgAgentSetupMacosHome(),
            fallbackHome = null,
        )

        is Platform.Desktop.Linux -> GpgAgentSetupSupportedPlatformContent(
            gpgHome = if (platform.isFlatpak) {
                GPG_AGENT_SETUP_LINUX_FLATPAK_HOME
            } else {
                GPG_AGENT_SETUP_LINUX_HOME
            },
            fallbackHome = GPG_AGENT_SETUP_LINUX_HOME_FALLBACK.takeUnless { platform.isFlatpak },
        )

        is Platform.Desktop.Windows -> GpgAgentSetupWindowsContent()

        else -> AgentSetupParagraph(
            text = stringResource(Res.string.gpg_agent_setup_unsupported_text),
        )
    }
}

private fun gpgAgentSetupMacosHome(): String =
    if (BuildKonfig.buildType == BUILD_TYPE_DEV) {
        GPG_AGENT_SETUP_MACOS_DEV_HOME
    } else {
        GPG_AGENT_SETUP_MACOS_RELEASE_HOME
    }

@Composable
private fun ColumnScope.GpgAgentSetupSupportedPlatformContent(
    gpgHome: String,
    fallbackHome: String?,
) {
    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_1_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_step_1_text),
    )
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_2_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_step_2_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = exportGpgHomeCommand(gpgHome),
    )
    fallbackHome?.let {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        AgentSetupBodyLabel(
            text = stringResource(Res.string.gpg_agent_setup_linux_home_fallback_note),
        )
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        AgentSetupCodeBlock(
            text = exportGpgHomeCommand(it),
        )
    }
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_3_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_step_3_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_IMPORT_CMD,
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_LIST_KEYS_CMD,
    )
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_4_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_step_4_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_VERIFY_CMD_LIST,
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_VERIFY_CMD_SIGN,
    )
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_5_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_step_5_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_GIT_CONFIG_CMD,
    )
    AgentSetupCodeBlock(
        text = "GNUPGHOME=\"${gpgHome.escapeDoubleQuoted()}\" git commit -S",
    )
}

private fun exportGpgHomeCommand(gpgHome: String): String =
    "export GNUPGHOME=\"${gpgHome.escapeDoubleQuoted()}\""

private fun String.escapeDoubleQuoted(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")

@Composable
private fun ColumnScope.GpgAgentSetupWindowsContent() {
    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_1_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_step_1_text),
    )
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.gpg_agent_setup_windows_step_2_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_windows_step_2_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_WINDOWS_PIPE,
    )
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_3_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_windows_step_3_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_IMPORT_CMD,
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_LIST_KEYS_CMD,
    )
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_4_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_windows_step_4_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = GPG_AGENT_SETUP_WINDOWS_VERIFY_CMD_LIST,
    )
}
