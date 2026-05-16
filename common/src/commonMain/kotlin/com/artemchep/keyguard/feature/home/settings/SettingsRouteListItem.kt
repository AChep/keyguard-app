package com.artemchep.keyguard.feature.home.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.feature.home.settings.debug.DebugSettingsRoute
import com.artemchep.keyguard.feature.home.settings.developer.DeveloperSettingsRoute
import com.artemchep.keyguard.feature.home.settings.notifications.NotificationsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.subscriptions.SubscriptionsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.watchtower.WatchtowerSettingsRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasAutofill
import com.artemchep.keyguard.platform.util.hasSubscription
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_appearance_text
import com.artemchep.keyguard.res.pref_item_appearance_title
import com.artemchep.keyguard.res.pref_item_autofill_text
import com.artemchep.keyguard.res.pref_item_autofill_title
import com.artemchep.keyguard.res.pref_item_dev_text
import com.artemchep.keyguard.res.pref_item_dev_title
import com.artemchep.keyguard.res.pref_item_developer_text
import com.artemchep.keyguard.res.pref_item_developer_title
import com.artemchep.keyguard.res.pref_item_notifications_text
import com.artemchep.keyguard.res.pref_item_notifications_title
import com.artemchep.keyguard.res.pref_item_other_text
import com.artemchep.keyguard.res.pref_item_other_title
import com.artemchep.keyguard.res.pref_item_security_text
import com.artemchep.keyguard.res.pref_item_security_title
import com.artemchep.keyguard.res.pref_item_subscription_text
import com.artemchep.keyguard.res.pref_item_subscription_title
import com.artemchep.keyguard.res.pref_item_watchtower_text
import com.artemchep.keyguard.res.pref_item_watchtower_title
import com.artemchep.keyguard.res.pref_section_options_title
import com.artemchep.keyguard.res.pref_section_premium_title
import org.kodein.di.compose.rememberInstance

sealed interface SettingsRouteListItem {
    val id: String
}

data class SettingsRouteListSection(
    override val id: String,
    val title: TextHolder?,
) : SettingsRouteListItem

data class SettingsRouteListAction(
    override val id: String,
    val title: TextHolder,
    val text: TextHolder,
    val icon: ImageVector? = null,
    val route: Route,
) : SettingsRouteListItem

@Composable
fun rememberSettingsRouteListItems(
    autofillRoute: Route,
    securityRoute: Route,
    uiRoute: Route,
    otherRoute: Route,
    includeAutofill: Boolean = CurrentPlatform.hasAutofill() || !isRelease,
    includeWatchtower: Boolean = true,
    includeDeveloper: Boolean = true,
    includeNotifications: Boolean = !isRelease,
    includeDebug: Boolean = !isRelease,
): List<SettingsRouteListItem> {
    val config by rememberInstance<FlavorConfig>()
    return remember(
        config,
        autofillRoute,
        securityRoute,
        uiRoute,
        otherRoute,
        includeAutofill,
        includeWatchtower,
        includeDeveloper,
        includeNotifications,
        includeDebug,
    ) {
        listOfNotNull(
            SettingsRouteListSection(
                id = "section.premium",
                title = TextHolder.Res(Res.string.pref_section_premium_title),
            ).takeIf { CurrentPlatform.hasSubscription() && !config.isFreeAsBeer },
            SettingsRouteListAction(
                id = "subscription",
                title = TextHolder.Res(Res.string.pref_item_subscription_title),
                text = TextHolder.Res(Res.string.pref_item_subscription_text),
                route = SubscriptionsSettingsRoute,
            ).takeIf { CurrentPlatform.hasSubscription() && !config.isFreeAsBeer },
            SettingsRouteListSection(
                id = "section.options",
                title = TextHolder.Res(Res.string.pref_section_options_title),
            ),
            SettingsRouteListAction(
                id = "autofill",
                title = TextHolder.Res(Res.string.pref_item_autofill_title),
                text = TextHolder.Res(Res.string.pref_item_autofill_text),
                icon = Icons.Outlined.AutoAwesome,
                route = autofillRoute,
            ).takeIf { includeAutofill },
            SettingsRouteListAction(
                id = "security",
                title = TextHolder.Res(Res.string.pref_item_security_title),
                text = TextHolder.Res(Res.string.pref_item_security_text),
                icon = Icons.Outlined.Lock,
                route = securityRoute,
            ),
            SettingsRouteListAction(
                id = "developer",
                title = TextHolder.Res(Res.string.pref_item_developer_title),
                text = TextHolder.Res(Res.string.pref_item_developer_text),
                icon = Icons.Outlined.Code,
                route = DeveloperSettingsRoute,
            ).takeIf { includeDeveloper },
            SettingsRouteListAction(
                id = "watchtower",
                title = TextHolder.Res(Res.string.pref_item_watchtower_title),
                text = TextHolder.Res(Res.string.pref_item_watchtower_text),
                icon = Icons.Outlined.Security,
                route = WatchtowerSettingsRoute,
            ).takeIf { includeWatchtower },
            SettingsRouteListAction(
                id = "notifications",
                title = TextHolder.Res(Res.string.pref_item_notifications_title),
                text = TextHolder.Res(Res.string.pref_item_notifications_text),
                icon = Icons.Outlined.Notifications,
                route = NotificationsSettingsRoute,
            ).takeIf { includeNotifications },
            SettingsRouteListAction(
                id = "display",
                title = TextHolder.Res(Res.string.pref_item_appearance_title),
                text = TextHolder.Res(Res.string.pref_item_appearance_text),
                icon = Icons.Outlined.ColorLens,
                route = uiRoute,
            ),
            SettingsRouteListAction(
                id = "debug",
                title = TextHolder.Res(Res.string.pref_item_dev_title),
                text = TextHolder.Res(Res.string.pref_item_dev_text),
                icon = Icons.Outlined.DeveloperBoard,
                route = DebugSettingsRoute,
            ).takeIf { includeDebug },
            SettingsRouteListAction(
                id = "about",
                title = TextHolder.Res(Res.string.pref_item_other_title),
                text = TextHolder.Res(Res.string.pref_item_other_text),
                icon = Icons.Outlined.Info,
                route = otherRoute,
            ),
        )
    }
}
