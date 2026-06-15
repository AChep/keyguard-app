package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarRate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.ChevronIcon
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingRateAppProvider(
    directDI: DirectDI,
) = settingRateAppProvider()

fun settingRateAppProvider(): SettingComponent = kotlin.run {
    // Do not render the field if there's nothing
    // to show its full content in.
    if (!CurrentPlatform.hasBrowser()) {
        return@run flowOf(null)
    }

    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "rate",
                "play",
                "google",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingRateAppItem(
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = "https://play.google.com/store/apps/details?id=com.artemchep.keyguard",
                )
                navigationController.queue(intent)
            },
        )
    }
    flowOf(item)
}

@Composable
fun SettingRateAppItem(
    onClick: (() -> Unit)?,
) {
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.StarRate,
        trailing = {
            ChevronIcon()
        },
        title = stringResource(Res.string.pref_item_rate_on_play_store_title),
        onClick = onClick,
    )
}
