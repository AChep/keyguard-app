package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.gpgagent.help.GpgAgentSetupRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI

fun settingGpgAgentSetupProvider(
    directDI: DirectDI,
) = settingGpgAgentSetupProvider()

fun settingGpgAgentSetupProvider(): SettingComponent = kotlin.run {
    val item = SettingIi(
        platformClasses = listOf(
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "gpg",
                "gnupg",
                "agent",
                "git",
                "setup",
                "gnupghome",
                "socket",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingGpgAgentSetup(
            onClick = {
                navigationController.queue(
                    NavigationIntent.NavigateToRoute(
                        route = GpgAgentSetupRoute,
                    ),
                )
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingGpgAgentSetup(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Terminal,
        trailing = {
            ChevronIcon()
        },
        title = stringResource(Res.string.pref_item_gpg_agent_setup_title),
        onClick = onClick,
    )
}
