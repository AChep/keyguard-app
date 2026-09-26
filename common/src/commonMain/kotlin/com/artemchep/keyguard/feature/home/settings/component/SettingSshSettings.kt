package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.ssh.SshSettingsRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardSshKey
import kotlinx.coroutines.flow.flow
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingSshSettingsProvider(
    koinScope: Scope,
) = settingSshSettingsProvider(
    windowCoroutineScope = koinScope.get(),
)

fun settingSshSettingsProvider(
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = flow {
    // I can not imagine many people running the
    // SSH agent on their watch.
    if (CurrentPlatform.hasWatch()) {
        emit(null)
        return@flow
    }

    val item = SettingIi(
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
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingSshSettings(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(SshSettingsRoute)
                navigationController.queue(intent)
            },
        )
    }
    emit(item)
}

@Composable
private fun SettingSshSettings(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.KeyguardSshKey,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_ssh_agent_title),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
