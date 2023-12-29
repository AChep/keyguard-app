package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarRate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

fun settingRateAppProvider(
    directDI: DirectDI,
) = settingRateAppProvider()

fun settingRateAppProvider(): SettingComponent = kotlin.run {
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
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.StarRate),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_rate_on_play_store_title),
            )
        },
        onClick = onClick,
    )
}
