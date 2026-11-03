package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.service.cli.agent.CliAgentHost
import com.artemchep.keyguard.common.service.environment.EnvironmentMcp
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.environments_mcp_enable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingEnvironmentsMcpProvider(
    koinScope: Scope,
) = environmentsSettingProvider {
    settingEnvironmentsMcpProvider(
        environmentMcp = koinScope.get(),
        cliAgentHost = koinScope.get(),
        windowCoroutineScope = koinScope.get(),
    )
}

fun settingEnvironmentsMcpProvider(
    environmentMcp: EnvironmentMcp,
    cliAgentHost: CliAgentHost,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    environmentMcp.state,
    cliAgentHost.status,
) { state, status ->
    state.enabled to status
}
    .distinctUntilChanged()
    .map { (mcpEnabled, status) ->
        val onCheckedChange = { shouldEnable: Boolean ->
            windowCoroutineScope.launch {
                environmentMcp.setEnabled(shouldEnable)
            }
            Unit
        }

        SettingIi(
            platformClasses = environmentsPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf(
                    "environments",
                    "env",
                    "mcp",
                    "agent",
                ),
            ),
        ) {
            SettingEnvironmentsMcp(
                checked = mcpEnabled,
                // The agent also runs for local .env files, so its
                // status only describes MCP while MCP is enabled.
                status = status.takeIf { mcpEnabled },
                onCheckedChange = onCheckedChange,
            )
        }
    }

internal val environmentsPlatformClasses = listOf(Platform.Desktop::class)

internal inline fun environmentsSettingProvider(
    provider: () -> SettingComponent,
): SettingComponent = platformSettingProvider(environmentsPlatformClasses, provider)

@Composable
private fun SettingEnvironmentsMcp(
    checked: Boolean,
    status: AgentStatus?,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.Code,
        title = {
            Text(
                text = stringResource(Res.string.environments_mcp_enable),
            )
        },
        text = if (status != null) {
            // composable
            {
                AgentStatusText(
                    status = status,
                )
            }
        } else {
            null
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
