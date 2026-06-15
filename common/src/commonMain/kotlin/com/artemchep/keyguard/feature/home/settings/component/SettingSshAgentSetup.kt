package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.sshagent.help.SshAgentSetupRoute
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.Stub
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI

fun settingSshAgentSetupProvider(
    directDI: DirectDI,
) = settingSshAgentSetupProvider()

fun settingSshAgentSetupProvider(): SettingComponent = kotlin.run {
    // I can not imagine many people running the
    // SSH agent on their watch.
    if (CurrentPlatform.hasWatch()) {
        return@run flowOf(null)
    }

    val item = SettingIi(
        platformClasses = listOf(
            Platform.Mobile.Android::class,
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "ssh",
                "agent",
                "git",
                "setup",
                "client",
                "identityagent",
                "ssh_auth_sock",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingSshAgentSetup(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = SshAgentSetupRoute,
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
private fun SettingSshAgentSetup(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Stub,
        trailing = {
            ChevronIcon()
        },
        title = stringResource(Res.string.pref_item_ssh_agent_setup_title),
        onClick = onClick,
    )
}
