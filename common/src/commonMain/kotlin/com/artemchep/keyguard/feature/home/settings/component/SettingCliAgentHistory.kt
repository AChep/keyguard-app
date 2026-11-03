package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.usecase.GetCliAgentUsageHistoryCount
import com.artemchep.keyguard.feature.cliagent.history.CliAgentHistoryRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingCliHistoryProvider(
    koinScope: Scope,
) = cliSettingProvider {
    settingCliAgentHistoryProvider(
        client = CliAgentUsageHistoryClient.CLI,
        getCliAgentUsageHistoryCount = koinScope.get(),
    )
}

fun settingEnvironmentsMcpHistoryProvider(
    koinScope: Scope,
) = environmentsSettingProvider {
    settingCliAgentHistoryProvider(
        client = CliAgentUsageHistoryClient.MCP,
        getCliAgentUsageHistoryCount = koinScope.get(),
    )
}

fun settingEnvironmentsHistoryProvider(
    koinScope: Scope,
) = environmentsSettingProvider {
    settingCliAgentHistoryProvider(
        client = CliAgentUsageHistoryClient.ENV,
        getCliAgentUsageHistoryCount = koinScope.get(),
    )
}

/** The request log of one [client] of the CLI agent. */
fun settingCliAgentHistoryProvider(
    client: CliAgentUsageHistoryClient,
    getCliAgentUsageHistoryCount: GetCliAgentUsageHistoryCount,
): SettingComponent = getCliAgentUsageHistoryCount(client).map { count ->
    SettingIi(
        platformClasses = cliPlatformClasses,
        search = SettingIi.Search(
            group = "developer",
            tokens = listOf(
                client.name.lowercase(),
                "agent",
                "request",
                "log",
                "history",
                "audit",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingCliAgentHistory(
            count = count,
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = CliAgentHistoryRoute(client = client),
                )
                navigationController.queue(intent)
            },
        )
    }
}

@Composable
private fun SettingCliAgentHistory(
    count: Long,
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.History,
        badge = count.toString(),
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
