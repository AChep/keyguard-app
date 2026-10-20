package com.artemchep.keyguard.feature.agent.help

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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.learn_more
import com.artemchep.keyguard.res.ssh_agent_setup_android_termux_step_1_text
import com.artemchep.keyguard.res.ssh_agent_setup_android_termux_step_1_title
import com.artemchep.keyguard.res.ssh_agent_setup_android_termux_step_2_text
import com.artemchep.keyguard.res.ssh_agent_setup_android_termux_step_2_title
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

private const val TERMUX_URL =
    "https://github.com/termux/termux-app?tab=readme-ov-file#installation"

/** Adds Keyguard termux repo as one of the sources */
private const val TERMUX_PKG_ADD_REPO = $$"""mkdir -p "$PREFIX/etc/apt/keyrings"
mkdir -p "$PREFIX/etc/apt/sources.list.d"

KG_KEY_PATH="$PREFIX/etc/apt/keyrings/keyguard-repo.gpg"

curl -fsSL https://gh.artemchep.com/keyguard-repo-termux/keyguard-repo.gpg \
  -o $KG_KEY_PATH

echo "deb [signed-by=$KG_KEY_PATH] https://gh.artemchep.com/keyguard-repo-termux/ stable main" \
  > $PREFIX/etc/apt/sources.list.d/keyguard.list

pkg update"""

/**
 * The steps that install Termux and, from Keyguard's APT repository, the
 * helper package that [installCommand] names.
 */
@Composable
internal fun ColumnScope.AgentSetupTermuxInstall(
    installCommand: String,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
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
        text = TERMUX_PKG_ADD_REPO,
    )
    AgentSetupCodeBlock(
        text = installCommand,
    )
}
