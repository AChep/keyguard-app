package com.artemchep.keyguard.feature.home.settings.subscriptions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.feature.home.settings.Setting
import com.artemchep.keyguard.feature.home.settings.SettingPaneContent
import com.artemchep.keyguard.feature.home.settings.SettingPaneItem
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWebPurchase
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource

@Composable
fun SubscriptionsSettingsScreen() {
    val items = rememberSettingsSubscriptionsItems()
    SettingPaneContent(
        title = stringResource(Res.string.settings_subscriptions_header_title),
        items = items,
    )
}

@Composable
fun rememberSettingsSubscriptionsItems(
): ImmutableList<SettingPaneItem> {
    return remember {
        val subscriptions = SettingPaneItem.Item(Setting.SUBSCRIPTIONS)
        val other = SettingPaneItem.Group(
            key = "other",
            title = Res.string.misc.wrap(),
            list = persistentListOf(
                SettingPaneItem.Item(Setting.SUBSCRIPTIONS_IN_STORE),
                SettingPaneItem.Item(Setting.ABOUT_TEAM),
                SettingPaneItem.Item(Setting.FEEDBACK_APP),
            ),
        )
        val sharing = SettingPaneItem.Group(
            key = "sharing",
            list = persistentListOf(
                SettingPaneItem.Item(Setting.LICENSE_PURCHASE),
                SettingPaneItem.Item(Setting.LICENSE_CLAIM),
                SettingPaneItem.Item(Setting.LICENSE_REDEEM),
            ),
        )
        // Without a store, buying and linking a license
        // are the main actions of the screen.
        if (CurrentPlatform.hasWebPurchase()) {
            return@remember persistentListOf(subscriptions, sharing, other)
        }
        persistentListOf(subscriptions, other, sharing)
    }
}
