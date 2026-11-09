package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.applink.AppLinkRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_app_link_text
import com.artemchep.keyguard.res.pref_item_app_link_title
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flow
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

// The settings hub resolves every provider with its scope.
@Suppress("UnusedParameter")
fun settingAppLinkProvider(
    koinScope: Scope,
) = settingAppLinkProvider()

/** Opens the linked devices; a watch never links its app. */
fun settingAppLinkProvider(): SettingComponent = flow {
    if (CurrentPlatform.hasWatch()) {
        emit(null)
        return@flow
    }

    val item = SettingIi(
        search = SettingIi.Search(
            group = "app_link",
            tokens = listOf(
                "link",
                "linked",
                "devices",
                "sync",
                "forwarders",
                "wordlists",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingAppLink(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(AppLinkRoute)
                navigationController.queue(intent)
            },
        )
    }
    emit(item)
}

@Composable
private fun SettingAppLink(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Devices,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_app_link_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.pref_item_app_link_text),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
