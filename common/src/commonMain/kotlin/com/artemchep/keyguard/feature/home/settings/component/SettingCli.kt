package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Text
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cli_enable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingCliProvider(
    koinScope: Scope,
) = cliSettingProvider {
    settingCliProvider(
        cliService = koinScope.get(),
        windowCoroutineScope = koinScope.get(),
    )
}

fun settingCliProvider(
    cliService: CliService,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = cliService.state
    .map { state ->
        // Builds that serve no command of the CLI, such as Flatpak, do not offer it.
        state.enabled.takeIf { state.supported }
    }
    .distinctUntilChanged()
    .map { enabled ->
        if (enabled == null) {
            return@map null
        }

        val onCheckedChange = { shouldEnable: Boolean ->
            windowCoroutineScope.launch {
                cliService.setEnabled(shouldEnable)
            }
            Unit
        }

        SettingIi(
            platformClasses = cliPlatformClasses,
            search = SettingIi.Search(
                group = "developer",
                tokens = listOf(
                    "cli",
                    "command",
                    "terminal",
                    "run",
                    "environments",
                ),
            ),
        ) {
            LocalSettingPaneComponents.current.KgSwitch(
                icon = Icons.Outlined.Terminal,
                title = {
                    Text(
                        text = stringResource(Res.string.cli_enable),
                    )
                },
                checked = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }

internal val cliPlatformClasses = listOf(Platform.Desktop::class)

internal inline fun cliSettingProvider(
    provider: () -> SettingComponent,
): SettingComponent = platformSettingProvider(cliPlatformClasses, provider)
