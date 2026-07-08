package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.GetGpgUsageHistoryCount
import com.artemchep.keyguard.feature.gpgagent.history.GpgAgentHistoryRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingGpgAgentHistoryProvider(
    directDI: DirectDI,
) = settingGpgAgentHistoryProvider(
    getGpgUsageHistoryCount = directDI.instance(),
)

fun settingGpgAgentHistoryProvider(
    getGpgUsageHistoryCount: GetGpgUsageHistoryCount,
): SettingComponent = getGpgUsageHistoryCount().map { count ->
    SettingIi(
        platformClasses = listOf(
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "gpg",
                "gnupg",
                "git",
                "agent",
                "history",
                "usage",
                "audit",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingGpgAgentHistory(
            count = count,
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = GpgAgentHistoryRoute(),
                )
                navigationController.queue(intent)
            },
        )
    }
}

@Composable
private fun SettingGpgAgentHistory(
    count: Long,
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.History,
        badge = count.toString(),
        title = {
            Text(
                text = stringResource(Res.string.pref_item_gpg_agent_history_title),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
