package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.navigation.NavigationItemsSettingsRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI

@Suppress("UNUSED_PARAMETER")
fun settingNavigationItemsProvider(
    directDI: DirectDI,
) = settingNavigationItemsProvider()

fun settingNavigationItemsProvider(): SettingComponent {
    if (CurrentPlatform.hasWatch()) {
        return flowOf(null)
    }

    return flowOf(
        SettingIi(
            search = SettingIi.Search(
                group = "ui",
                tokens = listOf(
                    "navigation",
                    "home",
                    "tab",
                    "filter",
                ),
            ),
        ) {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            SettingNavigationItems(
                onClick = {
                    navigationController.queue(
                        NavigationIntent.NavigateToRoute(
                            route = NavigationItemsSettingsRoute,
                        ),
                    )
                },
            )
        },
    )
}

@Composable
private fun SettingNavigationItems(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Home,
        title = stringResource(Res.string.pref_item_nav_items_title),
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
