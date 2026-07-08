package com.artemchep.keyguard.feature.sshagent.help

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.build.BuildKonfig
import com.artemchep.keyguard.feature.agent.help.AgentSetupBodyLabel
import com.artemchep.keyguard.feature.agent.help.AgentSetupCodeBlock
import com.artemchep.keyguard.feature.agent.help.AgentSetupParagraph
import com.artemchep.keyguard.feature.agent.help.AgentSetupScaffold
import com.artemchep.keyguard.feature.agent.help.AgentSetupSectionDivider
import com.artemchep.keyguard.feature.home.vault.component.LargeSection
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.tabs.TabItem
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource


// Arguments to some keywords can be expanded at runtime from environment variables on the client
// by enclosing them in ${}, for example ${HOME}/.ssh would refer to the user's .ssh directory.
// If a specified environment variable does not exist then an error will be returned and
// the setting for that keyword will be ignored.
//
// https://github.com/AChep/keyguard-app/issues/1440
private const val BUILD_TYPE_DEV = "DEV"
private const val SSH_AGENT_SETUP_MACOS_DEV_SOCKET =
    "/tmp/keyguard-<UID>/ssh-agent.sock"
private const val SSH_AGENT_SETUP_MACOS_RELEASE_SOCKET =
    $$"${HOME}/Library/Group Containers/com.artemchep.keyguard/ssh-agent.sock"
private const val SSH_AGENT_SETUP_LINUX_SOCKET =
    $$"${XDG_RUNTIME_DIR}/keyguard-ssh-agent.sock"
private const val SSH_AGENT_SETUP_LINUX_SOCKET_FALLBACK =
    "/tmp/keyguard-<UID>/ssh-agent.sock"
private const val SSH_AGENT_SETUP_LINUX_FLATPAK_SOCKET =
    $$"${XDG_RUNTIME_DIR}/app/com.artemchep.keyguard/ssh-agent.sock"
private const val SSH_AGENT_SETUP_OPTION_IDENTITYAGENT_FILE =
    "~/.ssh/config"
private const val SSH_AGENT_SETUP_VERIFY_CMD_LIST =
    "ssh-add -L"
private const val SSH_AGENT_SETUP_VERIFY_CMD_CONNECT =
    "ssh -T git@github.com"

private const val TERMUX_URL =
    "https://github.com/termux/termux-app?tab=readme-ov-file#installation"

/** Adds Keyguard termux repo as one of the sources */
private const val SSH_AGENT_TERMUX_PKG_ADD_REPO = $$"""mkdir -p "$PREFIX/etc/apt/keyrings"
mkdir -p "$PREFIX/etc/apt/sources.list.d"

KG_KEY_PATH="$PREFIX/etc/apt/keyrings/keyguard-repo.gpg"

curl -fsSL https://gh.artemchep.com/keyguard-repo-termux/keyguard-repo.gpg \
  -o $KG_KEY_PATH

echo "deb [signed-by=$KG_KEY_PATH] https://gh.artemchep.com/keyguard-repo-termux/ stable main" \
  > $PREFIX/etc/apt/sources.list.d/keyguard.list

pkg update"""

private const val SSH_AGENT_TERMUX_PKG_INSTALL = """pkg install keyguard-android-ssh-agent"""

private const val SSH_AGENT_TERMUX_PKG_SETUP_CURRENT =
    $$"""eval "$("$PREFIX/bin/keyguard-android-ssh-agent" -a "$PREFIX/tmp/keyguard-ssh-agent.sock")""""

private const val SSH_AGENT_TERMUX_PKG_SETUP_STARTUP =
    $$"""if [ -x "$PREFIX/bin/keyguard-android-ssh-agent" ]; then
  eval "$("$PREFIX/bin/keyguard-android-ssh-agent" -a "$PREFIX/tmp/keyguard-ssh-agent.sock")"
fi"""

@Composable
fun SshAgentSetupScreen() {
    AgentSetupScaffold(
        title = stringResource(Res.string.ssh_agent_setup_header_title),
    ) {
        SshAgentSetupScreenContent()
    }
}

@Composable
private fun ColumnScope.SshAgentSetupScreenContent() {
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_intro),
    )

    when (val platform = CurrentPlatform) {
        is Platform.Desktop.MacOS -> SshAgentSetupSupportedPlatformContent(
            isMacOS = true,
            isFlatpak = false,
        )

        is Platform.Desktop.Linux -> SshAgentSetupSupportedPlatformContent(
            isMacOS = false,
            isFlatpak = platform.isFlatpak,
        )

        is Platform.Desktop.Windows -> SshAgentSetupUnsupportedPlatformContent()

        is Platform.Mobile.Android -> SshAgentSetupAndroidPlatformContent()

        else -> Unit
    }
}

@Composable
private fun ColumnScope.SshAgentSetupSupportedPlatformContent(
    isMacOS: Boolean,
    isFlatpak: Boolean,
) {
    val sshAgentSocketPath = when {
        isMacOS -> sshAgentSetupMacosSocket()
        isFlatpak -> SSH_AGENT_SETUP_LINUX_FLATPAK_SOCKET
        else -> SSH_AGENT_SETUP_LINUX_SOCKET
    }

    Section(
        text = stringResource(Res.string.ssh_agent_setup_step_1_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_step_1_text),
    )
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.ssh_agent_setup_step_2_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_step_2_text),
    )

    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = sshAgentSocketPath,
    )
    if (!isMacOS && !isFlatpak) {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        AgentSetupBodyLabel(
            text = stringResource(Res.string.ssh_agent_setup_linux_socket_fallback_note),
        )
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        AgentSetupCodeBlock(
            text = SSH_AGENT_SETUP_LINUX_SOCKET_FALLBACK,
        )
    }
    Spacer(modifier = Modifier.height(24.dp))

    val options = remember {
        persistentListOf(
            SetupOption.SetSshAuthSock,
            SetupOption.UseIdentityAgent,
        )
    }
    var selectedOption by rememberSaveable {
        mutableStateOf(SetupOption.SetSshAuthSock)
    }
    SegmentedButtonGroup(
        modifier = Modifier.padding(
            horizontal = Dimens.buttonHorizontalPadding,
        ),
        tabState = rememberUpdatedState(newValue = selectedOption),
        tabs = options,
        onClick = { option ->
            selectedOption = option
        },
    )

    Spacer(
        modifier = Modifier
            .height(16.dp),
    )

    when (selectedOption) {
        SetupOption.SetSshAuthSock -> {
            AgentSetupBodyLabel(
                text = stringResource(Res.string.ssh_agent_setup_option_env_label),
            )
            Spacer(
                modifier = Modifier
                    .height(4.dp),
            )
            AgentSetupCodeBlock(
                text = sshAuthSockCommand(sshAgentSocketPath),
            )
        }

        SetupOption.UseIdentityAgent -> {
            AgentSetupBodyLabel(
                text = stringResource(Res.string.ssh_agent_setup_option_identityagent_label),
            )
            Spacer(
                modifier = Modifier
                    .height(4.dp),
            )
            AgentSetupCodeBlock(
                file = SSH_AGENT_SETUP_OPTION_IDENTITYAGENT_FILE,
                text = identityAgentConfig(sshAgentSocketPath),
            )
        }
    }
    AgentSetupSectionDivider()

    Section(
        text = stringResource(Res.string.ssh_agent_setup_step_3_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_step_3_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = SSH_AGENT_SETUP_VERIFY_CMD_LIST,
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = SSH_AGENT_SETUP_VERIFY_CMD_CONNECT,
    )
}

private fun sshAgentSetupMacosSocket(): String =
    if (BuildKonfig.buildType == BUILD_TYPE_DEV) {
        SSH_AGENT_SETUP_MACOS_DEV_SOCKET
    } else {
        SSH_AGENT_SETUP_MACOS_RELEASE_SOCKET
    }

@Composable
private fun ColumnScope.SshAgentSetupAndroidPlatformContent() {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    LargeSection(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_text),
    )
    Section(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_1_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_1_text),
    )
    TextButton(
        modifier = Modifier
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        onClick = {
            val intent = NavigationIntent.NavigateToBrowser(
                url = TERMUX_URL,
            )
            navigationController.queue(intent)
        },
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .width(Dimens.buttonIconPadding),
        )
        Text(
            text = stringResource(Res.string.learn_more),
        )
    }
    Section(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_2_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_2_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = SSH_AGENT_TERMUX_PKG_ADD_REPO,
    )
    AgentSetupCodeBlock(
        text = SSH_AGENT_TERMUX_PKG_INSTALL,
    )
    Section(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_3_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_3_shell_current),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = SSH_AGENT_TERMUX_PKG_SETUP_CURRENT,
    )
    Spacer(
        modifier = Modifier
            .height(8.dp),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_3_shell_startup),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = SSH_AGENT_TERMUX_PKG_SETUP_STARTUP,
    )
    Section(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_4_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_4_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    AgentSetupCodeBlock(
        text = SSH_AGENT_SETUP_VERIFY_CMD_LIST,
    )
    AgentSetupCodeBlock(
        text = SSH_AGENT_SETUP_VERIFY_CMD_CONNECT,
    )
    LargeSection(
        text = stringResource(Res.string.ssh_agent_setup_android_how_does_it_work_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_android_how_does_it_work_1),
    )
    Spacer(
        modifier = Modifier
            .height(8.dp),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_android_how_does_it_work_2),
    )
}

private fun sshAuthSockCommand(socketPath: String): String =
    "export SSH_AUTH_SOCK=\"${socketPath.escapeDoubleQuoted()}\""

private fun identityAgentConfig(socketPath: String): String =
    "Host *\n  IdentityAgent \"${socketPath.escapeDoubleQuoted()}\""

private fun String.escapeDoubleQuoted(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")

@Composable
private fun ColumnScope.SshAgentSetupUnsupportedPlatformContent() {
    Section(
        text = stringResource(Res.string.ssh_agent_setup_windows_title),
    )
    AgentSetupParagraph(
        text = stringResource(Res.string.ssh_agent_setup_windows_text),
    )
}

private enum class SetupOption(
    override val key: String,
    override val title: TextHolder,
) : TabItem {
    SetSshAuthSock(
        key = "set_ssh_auth_sock",
        title = TextHolder.Res(Res.string.ssh_agent_setup_option_env_tab),
    ),
    UseIdentityAgent(
        key = "use_identity_agent",
        title = TextHolder.Res(Res.string.ssh_agent_setup_option_identityagent_tab),
    ),
}
