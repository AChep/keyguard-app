package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

actual fun settingSubscriptionsPlayStoreProvider(
    directDI: DirectDI,
): SettingComponent = settingSubscriptionsPlayStoreProvider()

fun settingSubscriptionsPlayStoreProvider(): SettingComponent = kotlin.run {
    val item = SettingIi {
        SettingSubscriptionsPlayStore()
    }
    flowOf(item)
}

@Composable
private fun SettingSubscriptionsPlayStore() {
    val context by rememberUpdatedState(LocalContext.current)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.Settings),
        title = {
            Text(
                text = stringResource(
                    Res.strings.pref_item_premium_manage_subscription_on_play_store_title,
                ),
            )
        },
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val intent = run {
                val packageName = context.packageName
                val url =
                    "https://play.google.com/store/account/subscriptions?package=$packageName"
                NavigationIntent.NavigateToBrowser(url)
            }
            controller.queue(intent)
        },
    )
}
