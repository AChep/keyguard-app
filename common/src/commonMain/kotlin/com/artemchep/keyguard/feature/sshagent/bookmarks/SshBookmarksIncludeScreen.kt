package com.artemchep.keyguard.feature.sshagent.bookmarks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.agent.help.AgentAutomaticSetupRoute
import com.artemchep.keyguard.feature.agent.help.AgentSetupCodeBlock
import com.artemchep.keyguard.feature.agent.help.SetupAgent
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.close
import com.artemchep.keyguard.res.pref_item_ssh_agent_bookmarks_config_include_retry
import com.artemchep.keyguard.res.pref_item_ssh_agent_bookmarks_config_include_setup_action
import com.artemchep.keyguard.res.pref_item_ssh_agent_bookmarks_config_include_text
import com.artemchep.keyguard.res.pref_item_ssh_agent_bookmarks_config_include_title
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

private const val SSH_CONFIG_PATH = "~/.ssh/config"

@Composable
fun SshBookmarksIncludeScreen(
    include: String,
) {
    val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)
    Dialog(
        icon = icon(Icons.Outlined.Terminal),
        title = {
            Text(
                text = stringResource(Res.string.pref_item_ssh_agent_bookmarks_config_include_title),
            )
        },
        content = {
            SshBookmarksIncludeContent(
                include = include,
            )
        },
        actions = {
            TextButton(
                onClick = {
                    updatedNavigationController.queue(NavigationIntent.Pop)
                },
            ) {
                Text(stringResource(Res.string.close))
            }
            TextButton(
                onClick = {
                    // Setup adds the include while the setting is still off.
                    val route = AgentAutomaticSetupRoute(
                        agent = SetupAgent.SSH,
                        includeSshBookmarks = true,
                    )
                    val intent = NavigationIntent.Composite(
                        listOf(
                            NavigationIntent.Pop,
                            NavigationIntent.NavigateToRoute(route),
                        ),
                    )
                    updatedNavigationController.queue(intent)
                },
            ) {
                Text(stringResource(Res.string.pref_item_ssh_agent_bookmarks_config_include_setup_action))
            }
        },
    )
}

@Composable
private fun SshBookmarksIncludeContent(
    include: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.pref_item_ssh_agent_bookmarks_config_include_text),
        )
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        AgentSetupCodeBlock(
            text = include,
            file = SSH_CONFIG_PATH,
        )
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.pref_item_ssh_agent_bookmarks_config_include_retry),
        )
    }
}
