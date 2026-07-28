package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.gpgagent.keyserver.search.GpgKeyserverSearchRoute
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI

fun settingGpgKeyserverSearchProvider(
    directDI: DirectDI,
) = settingGpgKeyserverSearchProvider()

fun settingGpgKeyserverSearchProvider(): SettingComponent = flowOf(
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
                "keyserver",
                "public",
                "key",
                "keys",
                "search",
                "openpgp",
                "hkp",
                "vks",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingGpgKeyserverSearch(
            onClick = {
                val intent = NavigationIntent.NavigateToRoute(
                    route = GpgKeyserverSearchRoute,
                )
                navigationController.queue(intent)
            },
        )
    },
)

@Composable
private fun SettingGpgKeyserverSearch(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Public,
        title = {
            Text(
                text = stringResource(Res.string.pref_item_gpg_keyserver_search_title),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = onClick,
    )
}
