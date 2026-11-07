package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.GetSshUsageHistoryCount
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.sshagent.history.SshAgentHistoryRoute
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingSshAgentHistoryProvider(
    koinScope: Scope,
) = settingSshAgentHistoryProvider(
    getSshUsageHistoryCount = koinScope.get(),
    numberFormatter = koinScope.get(),
)

fun settingSshAgentHistoryProvider(
    getSshUsageHistoryCount: GetSshUsageHistoryCount,
    numberFormatter: NumberFormatter,
): SettingComponent = getSshUsageHistoryCount().map { count ->
    // I can not imagine many people running the
    // SSH agent on their watch.
    if (CurrentPlatform.hasWatch()) {
        return@map null
    }

    val badge = numberFormatter.formatCompactNumber(count)
    SettingIi(
        platformClasses = listOf(
            Platform.Mobile.Android::class,
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
            Platform.Desktop.Windows::class,
            Platform.Desktop.Other::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "ssh",
                "git",
                "agent",
                "history",
                "usage",
                "audit",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingSshAgentHistory(
            badge = badge,
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = SshAgentHistoryRoute(),
                )
                navigationController.queue(intent)
            },
        )
    }
}

@Composable
private fun SettingSshAgentHistory(
    badge: String,
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.History,
        badge = badge,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_agent_history_title),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
