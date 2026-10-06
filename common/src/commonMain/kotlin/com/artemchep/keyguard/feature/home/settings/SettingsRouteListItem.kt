package com.artemchep.keyguard.feature.home.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.feature.home.settings.backups.AutomaticBackupsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.debug.DebugSettingsRoute
import com.artemchep.keyguard.feature.home.settings.developer.DeveloperSettingsRoute
import com.artemchep.keyguard.feature.home.settings.notifications.NotificationsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.subscriptions.SubscriptionsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.watchtower.WatchtowerSettingsRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasAutofill
import com.artemchep.keyguard.platform.util.isRelease
import org.koin.compose.koinInject

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
    val config = koinInject<FlavorConfig>()
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
        settingsCatalog(
            config = config,
            includeAutofill = includeAutofill,
            includeWatchtower = includeWatchtower,
            includeDeveloper = includeDeveloper,
            includeNotifications = includeNotifications,
            includeDebug = includeDebug,
        ).map { item ->
            when (item) {
                is SettingsCatalogItem.Section -> SettingsRouteListSection(
                    id = item.id,
                    title = TextHolder.Res(item.title),
                )
                is SettingsCatalogItem.Action -> SettingsRouteListAction(
                    id = item.id,
                    title = TextHolder.Res(item.title),
                    text = TextHolder.Res(item.text),
                    icon = item.destination.icon(),
                    route = item.destination.route(autofillRoute, securityRoute, uiRoute, otherRoute),
                )
            }
        }
    }
}

private fun SettingsDestination.icon(): ImageVector? = when (this) {
    SettingsDestination.SUBSCRIPTION -> null
    SettingsDestination.AUTOFILL -> Icons.Outlined.AutoAwesome
    SettingsDestination.SECURITY -> Icons.Outlined.Lock
    SettingsDestination.AUTOMATIC_BACKUPS -> Icons.Outlined.Backup
    SettingsDestination.DEVELOPER -> Icons.Outlined.Code
    SettingsDestination.WATCHTOWER -> Icons.Outlined.Security
    SettingsDestination.NOTIFICATIONS -> Icons.Outlined.Notifications
    SettingsDestination.DISPLAY -> Icons.Outlined.ColorLens
    SettingsDestination.DEBUG -> Icons.Outlined.DeveloperBoard
    SettingsDestination.ABOUT -> Icons.Outlined.Info
}

private fun SettingsDestination.route(
    autofillRoute: Route,
    securityRoute: Route,
    uiRoute: Route,
    otherRoute: Route,
): Route = when (this) {
    SettingsDestination.SUBSCRIPTION -> SubscriptionsSettingsRoute
    SettingsDestination.AUTOFILL -> autofillRoute
    SettingsDestination.SECURITY -> securityRoute
    SettingsDestination.AUTOMATIC_BACKUPS -> AutomaticBackupsSettingsRoute
    SettingsDestination.DEVELOPER -> DeveloperSettingsRoute
    SettingsDestination.WATCHTOWER -> WatchtowerSettingsRoute
    SettingsDestination.NOTIFICATIONS -> NotificationsSettingsRoute
    SettingsDestination.DISPLAY -> uiRoute
    SettingsDestination.DEBUG -> DebugSettingsRoute
    SettingsDestination.ABOUT -> otherRoute
}
