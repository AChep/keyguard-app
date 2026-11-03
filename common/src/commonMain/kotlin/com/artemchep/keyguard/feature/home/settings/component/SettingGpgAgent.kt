package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AgentStatus
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetGpgAgentStatus
import com.artemchep.keyguard.common.usecase.PutGpgAgent
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.KeyguardGpgKey
import kotlinx.coroutines.flow.combine
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingGpgAgentProvider(
    koinScope: Scope,
) = settingGpgAgentProvider(
    getGpgAgent = koinScope.get(),
    getGpgAgentStatus = koinScope.get(),
    putGpgAgent = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

fun settingGpgAgentProvider(
    getGpgAgent: GetGpgAgent,
    getGpgAgentStatus: GetGpgAgentStatus,
    putGpgAgent: PutGpgAgent,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    getGpgAgent(),
    getGpgAgentStatus(),
) { gpgAgent, gpgAgentStatus ->
    val onCheckedChange = { shouldGpgAgent: Boolean ->
        putGpgAgent(shouldGpgAgent)
            .launchIn(windowCoroutineScope)
        Unit
    }

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
                "gpg",
                "gnupg",
                "git",
                "agent",
                "sign",
            ),
        ),
    ) {
        SettingGpgAgent(
            checked = gpgAgent,
            status = gpgAgentStatus,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingGpgAgent(
    checked: Boolean,
    status: AgentStatus,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.KeyguardGpgKey,
        title = {
            Text(
                text = stringResource(Res.string.agent_title, stringResource(Res.string.protocol_gpg)),
            )
        },
        text = {
            AgentStatusText(
                status = status,
            )
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
