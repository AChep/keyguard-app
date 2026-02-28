package com.artemchep.keyguard.feature.sshagent.help

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.LargeSection
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.tabs.TabItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import com.artemchep.keyguard.ui.util.HorizontalDivider
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

private const val SSH_AGENT_SETUP_MACOS_SOCKET =
    "\$HOME/Library/Group Containers/com.artemchep.keyguard/ssh-agent.sock"
private const val SSH_AGENT_SETUP_LINUX_SOCKET =
    "\$XDG_RUNTIME_DIR/keyguard-ssh-agent.sock"
private const val SSH_AGENT_SETUP_LINUX_SOCKET_FALLBACK =
    "/tmp/keyguard-<UID>/ssh-agent.sock"
private const val SSH_AGENT_SETUP_OPTION_ENV_UNIX =
    "export SSH_AUTH_SOCK=\"/path/to/keyguard-agent.sock\""
private const val SSH_AGENT_SETUP_OPTION_IDENTITYAGENT_FILE =
    "~/.ssh/config"
private const val SSH_AGENT_SETUP_OPTION_IDENTITYAGENT_CONTENT =
    "Host *\n  IdentityAgent /path/to/keyguard-agent.sock"
private const val SSH_AGENT_SETUP_VERIFY_CMD_LIST =
    "ssh-add -L"
private const val SSH_AGENT_SETUP_VERIFY_CMD_CONNECT =
    "ssh -T git@github.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshAgentSetupScreen() {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.ssh_agent_setup_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        SshAgentSetupScreenContent()
    }
}

@Composable
private fun ColumnScope.SshAgentSetupScreenContent() {
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_intro),
    )

    when (CurrentPlatform) {
        is Platform.Desktop.MacOS -> SshAgentSetupSupportedPlatformContent(
            isMacOS = true,
        )

        is Platform.Desktop.Linux -> SshAgentSetupSupportedPlatformContent(
            isMacOS = false,
        )

        is Platform.Desktop.Windows -> SshAgentSetupUnsupportedPlatformContent()
        else -> Unit
    }
}

@Composable
private fun ColumnScope.SshAgentSetupSupportedPlatformContent(
    isMacOS: Boolean,
) {
    Section(
        text = stringResource(Res.string.ssh_agent_setup_step_1_title),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_step_1_text),
    )
    SectionDivider()

    Section(
        text = stringResource(Res.string.ssh_agent_setup_step_2_title),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_step_2_text),
    )

    if (isMacOS) {
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        CopyableCodeBlock(
            text = SSH_AGENT_SETUP_MACOS_SOCKET,
        )
    } else {
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        CopyableCodeBlock(
            text = SSH_AGENT_SETUP_LINUX_SOCKET,
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        BodyLabel(
            text = stringResource(Res.string.ssh_agent_setup_linux_socket_fallback_note),
        )
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        CopyableCodeBlock(
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
            BodyLabel(
                text = stringResource(Res.string.ssh_agent_setup_option_env_label),
            )
            Spacer(
                modifier = Modifier
                    .height(4.dp),
            )
            CopyableCodeBlock(
                text = SSH_AGENT_SETUP_OPTION_ENV_UNIX,
            )
        }

        SetupOption.UseIdentityAgent -> {
            BodyLabel(
                text = stringResource(Res.string.ssh_agent_setup_option_identityagent_label),
            )
            Spacer(
                modifier = Modifier
                    .height(4.dp),
            )
            CopyableCodeBlock(
                file = SSH_AGENT_SETUP_OPTION_IDENTITYAGENT_FILE,
                text = SSH_AGENT_SETUP_OPTION_IDENTITYAGENT_CONTENT,
            )
        }
    }
    SectionDivider()

    Section(
        text = stringResource(Res.string.ssh_agent_setup_step_3_title),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_step_3_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    CopyableCodeBlock(
        text = SSH_AGENT_SETUP_VERIFY_CMD_LIST,
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    CopyableCodeBlock(
        text = SSH_AGENT_SETUP_VERIFY_CMD_CONNECT,
    )
}

@Composable
private fun ColumnScope.SshAgentSetupUnsupportedPlatformContent() {
    Section(
        text = stringResource(Res.string.ssh_agent_setup_windows_title),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_windows_text),
    )
}

@Composable
private fun BodyLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
            ),
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}

@Composable
private fun Paragraph(
    text: String,
) {
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = text,
    )
}

@Composable
private fun CopyableCodeBlock(
    text: String,
    file: String? = null,
) {
    val clipboardService by rememberInstance<ClipboardService>()
    val copyDescription = stringResource(Res.string.copy)
    FlatItemLayoutExpressive(
        content = {
            if (file != null) {
                Text(
                    modifier = Modifier
                        .alpha(DisabledEmphasisAlpha),
                    text = file,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
            }
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
            )
        },
        trailing = {
            IconButton(
                onClick = {
                    clipboardService.setPrimaryClip(
                        value = text,
                        concealed = false,
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = copyDescription,
                )
            }
        },
        enabled = true,
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier
            .padding(vertical = 16.dp),
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
