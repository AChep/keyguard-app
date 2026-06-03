package com.artemchep.keyguard.feature.sshagent.help

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.LargeSection
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.tabs.TabItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import com.artemchep.keyguard.ui.util.HorizontalDivider
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance


// Arguments to some keywords can be expanded at runtime from environment variables on the client
// by enclosing them in ${}, for example ${HOME}/.ssh would refer to the user's .ssh directory.
// If a specified environment variable does not exist then an error will be returned and
// the setting for that keyword will be ignored.
//
// https://github.com/AChep/keyguard-app/issues/1440
private const val SSH_AGENT_SETUP_MACOS_SOCKET =
    $$"${HOME}/Library/Group Containers/com.artemchep.keyguard/ssh-agent.sock"
private const val SSH_AGENT_SETUP_LINUX_SOCKET =
    $$"${XDG_RUNTIME_DIR}/keyguard-ssh-agent.sock"
private const val SSH_AGENT_SETUP_LINUX_SOCKET_FALLBACK =
    "/tmp/keyguard-<UID>/ssh-agent.sock"
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

        is Platform.Mobile.Android -> SshAgentSetupAndroidPlatformContent()

        else -> Unit
    }
}

@Composable
private fun ColumnScope.SshAgentSetupSupportedPlatformContent(
    isMacOS: Boolean,
) {
    val sshAgentSocketPath = if (isMacOS) {
        SSH_AGENT_SETUP_MACOS_SOCKET
    } else {
        SSH_AGENT_SETUP_LINUX_SOCKET
    }

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
            text = sshAgentSocketPath,
        )
    } else {
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        CopyableCodeBlock(
            text = sshAgentSocketPath,
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
                text = sshAuthSockCommand(sshAgentSocketPath),
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
                text = identityAgentConfig(sshAgentSocketPath),
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
private fun ColumnScope.SshAgentSetupAndroidPlatformContent() {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    LargeSection(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_title),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_text),
    )
    Section(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_1_title),
    )
    Paragraph(
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
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_2_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    CopyableCodeBlock(
        text = SSH_AGENT_TERMUX_PKG_ADD_REPO,
    )
    CopyableCodeBlock(
        text = SSH_AGENT_TERMUX_PKG_INSTALL,
    )
    Section(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_3_title),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_3_shell_current),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    CopyableCodeBlock(
        text = SSH_AGENT_TERMUX_PKG_SETUP_CURRENT,
    )
    Spacer(
        modifier = Modifier
            .height(8.dp),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_3_shell_startup),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    CopyableCodeBlock(
        text = SSH_AGENT_TERMUX_PKG_SETUP_STARTUP,
    )
    Section(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_4_title),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_android_termux_step_4_text),
    )
    Spacer(
        modifier = Modifier
            .height(4.dp),
    )
    CopyableCodeBlock(
        text = SSH_AGENT_SETUP_VERIFY_CMD_LIST,
    )
    CopyableCodeBlock(
        text = SSH_AGENT_SETUP_VERIFY_CMD_CONNECT,
    )
    LargeSection(
        text = stringResource(Res.string.ssh_agent_setup_android_how_does_it_work_title),
    )
    Paragraph(
        text = stringResource(Res.string.ssh_agent_setup_android_how_does_it_work_1),
    )
    Spacer(
        modifier = Modifier
            .height(8.dp),
    )
    Paragraph(
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
    val codeModifier = if (CurrentPlatform is Platform.Mobile) {
        // On mobile the gesture of swiping to reveal more is trivial
        // and intuitive, on desktop however that is most likely
        // blocked.
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    } else {
        Modifier
    }

    val clipboardService by rememberInstance<ClipboardService>()
    val copyDescription = stringResource(Res.string.copy)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                vertical = 4.dp,
                horizontal = Dimens.textHorizontalPadding,
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = true),
        ) {
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
            SelectionContainer {
                val isDark = MaterialTheme.colorScheme.isDark
                var code by remember(text) {
                    mutableStateOf(AnnotatedString(text))
                }

                // Async-ly highlight the syntax of the
                // code snippet.
                LaunchedEffect(text, isDark) {
                    runCatching {
                        code = withContext(Dispatchers.Default) {
                            val codeHighlights = Highlights.Builder()
                                .code(text)
                                .theme(SyntaxThemes.darcula(darkMode = isDark))
                                .language(SyntaxLanguage.SHELL)
                                .build()

                            buildAnnotatedString {
                                append(text)

                                // Highlight & bold special segments
                                codeHighlights.getHighlights()
                                    .filterIsInstance<ColorHighlight>()
                                    .forEach {
                                        addStyle(
                                            SpanStyle(color = Color(it.rgb).copy(alpha = 1f)),
                                            start = it.location.start,
                                            end = it.location.end,
                                        )
                                    }
                                codeHighlights.getHighlights()
                                    .filterIsInstance<BoldHighlight>()
                                    .forEach {
                                        addStyle(
                                            SpanStyle(fontWeight = FontWeight.Bold),
                                            start = it.location.start,
                                            end = it.location.end,
                                        )
                                    }
                            }
                        }
                    }
                }
                Text(
                    modifier = Modifier
                        .then(codeModifier),
                    text = code,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
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
    }
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
