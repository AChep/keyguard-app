package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SSH_BOOKMARKS_CONFIG_FILE_NAME
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksConfigStore
import com.artemchep.keyguard.common.service.sshagent.bookmarks.SshBookmarksSummary
import com.artemchep.keyguard.common.usecase.GetSshAgentBookmarksConfig
import com.artemchep.keyguard.common.usecase.PutSshAgentBookmarksConfig
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.sshagent.bookmarks.SshBookmarksIncludeRoute
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingSshAgentBookmarksConfigProvider(
    koinScope: Scope,
): SettingComponent {
    // Only platforms whose SSH clients read the generated directory have one.
    val sshBookmarksConfigStore = koinScope.getOrNull<SshBookmarksConfigStore>()
        ?: return flowOf(null)
    return settingSshAgentBookmarksConfigProvider(
        getSshAgentBookmarksConfig = koinScope.get(),
        putSshAgentBookmarksConfig = koinScope.get(),
        sshBookmarksConfigStore = sshBookmarksConfigStore,
        windowCoroutineScope = koinScope.get(),
    )
}

fun settingSshAgentBookmarksConfigProvider(
    getSshAgentBookmarksConfig: GetSshAgentBookmarksConfig,
    putSshAgentBookmarksConfig: PutSshAgentBookmarksConfig,
    sshBookmarksConfigStore: SshBookmarksConfigStore,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getSshAgentBookmarksConfig(),
    sshBookmarksConfigStore.summary,
) { enabled, summary ->
    val include = "Include \"${sshBookmarksConfigStore.configDirectory}/$SSH_BOOKMARKS_CONFIG_FILE_NAME\""

    SettingIi(
        platformClasses = listOf(
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
            Platform.Desktop.Windows::class,
            Platform.Desktop.Other::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "ssh",
                "agent",
                "bookmark",
                "bookmarks",
                "host",
                "hosts",
                "config",
                "include",
            ),
        ),
    ) {
        SettingSshAgentBookmarksConfig(
            checked = enabled,
            include = include,
            summary = summary.takeIf { enabled },
            onCheckedChange = rememberSshAgentBookmarksConfigChange(
                include = include,
                putSshAgentBookmarksConfig = putSshAgentBookmarksConfig,
                sshBookmarksConfigStore = sshBookmarksConfigStore,
                windowCoroutineScope = windowCoroutineScope,
            ),
        )
    }
}

/**
 * Turns the setting on only if `~/.ssh/config` includes the configuration,
 * since ssh ignores it until then; otherwise explains how to include it.
 */
@Composable
private fun rememberSshAgentBookmarksConfigChange(
    include: String,
    putSshAgentBookmarksConfig: PutSshAgentBookmarksConfig,
    sshBookmarksConfigStore: SshBookmarksConfigStore,
    windowCoroutineScope: WindowCoroutineScope,
): ((Boolean) -> Unit)? {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    if (busy) {
        return null
    }
    return { shouldGenerate: Boolean ->
        if (shouldGenerate) {
            busy = true
            scope.launch {
                try {
                    if (sshBookmarksConfigStore.isIncluded() == false) {
                        val route = SshBookmarksIncludeRoute(
                            include = include,
                        )
                        navigationController.queue(NavigationIntent.NavigateToRoute(route))
                    } else {
                        putSshAgentBookmarksConfig(true)
                            .launchIn(windowCoroutineScope)
                    }
                } finally {
                    busy = false
                }
            }
        } else {
            putSshAgentBookmarksConfig(false)
                .launchIn(windowCoroutineScope)
        }
    }
}

@Composable
private fun SettingSshAgentBookmarksConfig(
    checked: Boolean,
    include: String,
    summary: SshBookmarksSummary?,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Stub,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_ssh_agent_bookmarks_config_title),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.pref_item_ssh_agent_bookmarks_config_text),
                )
                if (summary != null) {
                    Spacer(
                        modifier = Modifier
                            .height(8.dp),
                    )
                    SettingSshAgentBookmarksConfigSummary(
                        summary = summary,
                    )
                }
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
                Text(
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    style = MaterialTheme.typography.bodySmall,
                    text = stringResource(Res.string.pref_item_ssh_agent_bookmarks_config_note),
                )
            }
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
private fun SettingSshAgentBookmarksConfigSummary(
    summary: SshBookmarksSummary,
) {
    Text(
        text = pluralStringResource(
            Res.plurals.pref_item_ssh_agent_bookmarks_config_hosts_plural,
            summary.hosts,
            summary.hosts,
        ),
    )
    if (summary.skipped > 0) {
        Text(
            color = MaterialTheme.colorScheme.error,
            text = pluralStringResource(
                Res.plurals.pref_item_ssh_agent_bookmarks_config_skipped_plural,
                summary.skipped,
                summary.skipped,
            ),
        )
    }
}
