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
private const val GPG_AGENT_SETUP_WINDOWS_RELEASE_HOME =
    "\$env:LOCALAPPDATA\\ArtemChepurnyi\\keyguard\\gnupg"
private const val GPG_AGENT_SETUP_WINDOWS_DEV_HOME =
    "\$env:LOCALAPPDATA\\ArtemChepurnyi\\keyguard-dev\\gnupg"
private const val GPG_AGENT_SETUP_IMPORT_CMD =
    "gpg --import /path/to/keyguard-public-key.asc"
private const val GPG_AGENT_SETUP_WINDOWS_IMPORT_CMD =
    "gpg --import \"C:\\path\\to\\keyguard-public-key.asc\""
private const val GPG_AGENT_SETUP_LIST_KEYS_CMD =
    "gpg --no-autostart --list-secret-keys --with-keygrip --keyid-format=long"
private const val GPG_AGENT_SETUP_VERIFY_CMD_LIST =
    $$"""GPG_AGENT_SOCKET="$(gpgconf --homedir "$GNUPGHOME" --list-dirs agent-socket)"
gpg-connect-agent --raw-socket "$GPG_AGENT_SOCKET" "KEYINFO --list" /bye"""
private const val GPG_AGENT_SETUP_WINDOWS_VERIFY_CMD_LIST =
    $$"""$env:GPG_AGENT_SOCKET = & gpgconf --homedir "$env:GNUPGHOME" --list-dirs agent-socket
gpg-connect-agent --raw-socket "$env:GPG_AGENT_SOCKET" "KEYINFO --list" /bye"""
private const val GPG_AGENT_SETUP_VERIFY_CMD_SIGN =
    $$"""printf "Keyguard GPG agent test\n" | gpg --no-autostart --local-user YOUR_KEY_FINGERPRINT --clearsign"""
private const val GPG_AGENT_SETUP_WINDOWS_VERIFY_CMD_SIGN =
    "\"Keyguard GPG agent test\" | gpg --no-autostart --local-user YOUR_KEY_FINGERPRINT --clearsign"
private const val GPG_AGENT_SETUP_GIT_CONFIG_CMD = """git config --local user.signingkey YOUR_KEY_FINGERPRINT
git config --local commit.gpgsign true
git config --local gpg.format openpgp
git config --local gpg.program gpg"""
private const val GPG_AGENT_SETUP_WINDOWS_GIT_CONFIG_CMD = $$"""$gpgProgram = (Get-Command gpg.exe -CommandType Application).Source
if ($gpgProgram -like "*\Git\usr\bin\gpg.exe") { throw "Configure PATH to use native GnuPG first." }
git config --local user.signingkey YOUR_KEY_FINGERPRINT
git config --local commit.gpgsign true
git config --local gpg.format openpgp
git config --local gpg.program "$gpgProgram""""

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
        is Platform.Desktop.MacOS -> {
            val gpgHome = gpgAgentSetupMacosHome()
            GpgAgentSetupSupportedPlatformContent(
                commands = unixGpgAgentSetupCommands(gpgHome),
                fallbackHomeCommand = null,
                prerequisiteNote = null,
            )
        }

        is Platform.Desktop.Linux -> {
            val gpgHome = if (platform.isFlatpak) {
                GPG_AGENT_SETUP_LINUX_FLATPAK_HOME
            } else {
                GPG_AGENT_SETUP_LINUX_HOME
            }
            val fallbackHome = GPG_AGENT_SETUP_LINUX_HOME_FALLBACK
                .takeUnless { platform.isFlatpak }
            GpgAgentSetupSupportedPlatformContent(
                commands = unixGpgAgentSetupCommands(gpgHome),
                fallbackHomeCommand = fallbackHome?.let(::exportGpgHomeCommand),
                prerequisiteNote = null,
            )
        }

        is Platform.Desktop.Windows -> GpgAgentSetupSupportedPlatformContent(
            commands = windowsGpgAgentSetupCommands(gpgAgentSetupWindowsHome()),
            fallbackHomeCommand = null,
            prerequisiteNote = stringResource(Res.string.gpg_agent_setup_windows_native_gnupg_note),
        )

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

private fun gpgAgentSetupWindowsHome(): String =
    if (BuildKonfig.buildType.equals("RELEASE", ignoreCase = true)) {
        GPG_AGENT_SETUP_WINDOWS_RELEASE_HOME
    } else {
        GPG_AGENT_SETUP_WINDOWS_DEV_HOME
    }

@Composable
private fun ColumnScope.GpgAgentSetupSupportedPlatformContent(
    commands: GpgAgentSetupCommands,
    fallbackHomeCommand: String?,
    prerequisiteNote: String?,
) {
    Section(
        text = stringResource(Res.string.gpg_agent_setup_step_1_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.gpg_agent_setup_step_1_text),
    )
    prerequisiteNote?.let { note ->
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        AgentSetupBodyLabel(text = note)
    }
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
        text = commands.configureHome,
    )
    fallbackHomeCommand?.let { command ->
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
            text = command,
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
        text = commands.importPublicKey,
    )
    AgentSetupCodeBlock(
        text = commands.listKeys,
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
        text = commands.verifyAgent,
    )
    AgentSetupCodeBlock(
        text = commands.signMessage,
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
        text = commands.configureGit,
    )
    AgentSetupCodeBlock(
        text = commands.signGitCommit,
    )
}

private data class GpgAgentSetupCommands(
    val configureHome: String,
    val importPublicKey: String,
    val listKeys: String,
    val verifyAgent: String,
    val signMessage: String,
    val configureGit: String,
    val signGitCommit: String,
)

private fun unixGpgAgentSetupCommands(gpgHome: String) = GpgAgentSetupCommands(
    configureHome = exportGpgHomeCommand(gpgHome),
    importPublicKey = GPG_AGENT_SETUP_IMPORT_CMD,
    listKeys = GPG_AGENT_SETUP_LIST_KEYS_CMD,
    verifyAgent = GPG_AGENT_SETUP_VERIFY_CMD_LIST,
    signMessage = GPG_AGENT_SETUP_VERIFY_CMD_SIGN,
    configureGit = GPG_AGENT_SETUP_GIT_CONFIG_CMD,
    signGitCommit = "GNUPGHOME=\"${gpgHome.escapeDoubleQuoted()}\" git commit -S",
)

private fun windowsGpgAgentSetupCommands(gpgHome: String): GpgAgentSetupCommands {
    val configureHome = "\$env:GNUPGHOME = \"$gpgHome\""
    return GpgAgentSetupCommands(
        configureHome = configureHome,
        importPublicKey = GPG_AGENT_SETUP_WINDOWS_IMPORT_CMD,
        listKeys = GPG_AGENT_SETUP_LIST_KEYS_CMD,
        verifyAgent = GPG_AGENT_SETUP_WINDOWS_VERIFY_CMD_LIST,
        signMessage = GPG_AGENT_SETUP_WINDOWS_VERIFY_CMD_SIGN,
        configureGit = GPG_AGENT_SETUP_WINDOWS_GIT_CONFIG_CMD,
        signGitCommit = "$configureHome\ngit commit -S",
    )
}

private fun exportGpgHomeCommand(gpgHome: String): String =
    "export GNUPGHOME=\"${gpgHome.escapeDoubleQuoted()}\""

private fun String.escapeDoubleQuoted(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
