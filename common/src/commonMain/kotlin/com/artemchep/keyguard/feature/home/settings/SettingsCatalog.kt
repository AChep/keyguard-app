package com.artemchep.keyguard.feature.home.settings

import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasAutofill
import com.artemchep.keyguard.platform.util.hasSubscription
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_appearance_text
import com.artemchep.keyguard.res.pref_item_appearance_title
import com.artemchep.keyguard.res.pref_item_autofill_text
import com.artemchep.keyguard.res.pref_item_autofill_title
import com.artemchep.keyguard.res.pref_item_automatic_backups_text
import com.artemchep.keyguard.res.pref_item_automatic_backups_title
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
import org.jetbrains.compose.resources.StringResource

/** Stable destinations; renderers supply their own routes and icons. */
enum class SettingsDestination(val id: String) {
    SUBSCRIPTION("subscription"),
    AUTOFILL("autofill"),
    SECURITY("security"),
    AUTOMATIC_BACKUPS("automatic_backups"),
    DEVELOPER("developer"),
    WATCHTOWER("watchtower"),
    NOTIFICATIONS("notifications"),
    DISPLAY("display"),
    DEBUG("debug"),
    ABOUT("about"),
}

sealed interface SettingsCatalogItem {
    val id: String
    val title: StringResource

    data class Section(
        override val id: String,
        override val title: StringResource,
    ) : SettingsCatalogItem

    data class Action(
        val destination: SettingsDestination,
        override val title: StringResource,
        val text: StringResource,
    ) : SettingsCatalogItem {
        override val id: String get() = destination.id
    }
}

/** Ordering and visibility shared by the Compose and native settings lists. */
fun settingsCatalog(
    config: FlavorConfig,
    platform: Platform = CurrentPlatform,
    release: Boolean = isRelease,
    includeAutofill: Boolean = platform.hasAutofill() || !release,
    includeWatchtower: Boolean = true,
    includeDeveloper: Boolean = true,
    includeNotifications: Boolean = !release,
    includeDebug: Boolean = !release,
    includeAutomaticBackups: Boolean = (platform is Platform.Desktop || platform is Platform.Mobile.Android) &&
        (!platform.hasWatch() || !release),
): List<SettingsCatalogItem> = listOfNotNull(
    SettingsCatalogItem.Section(
        id = "section.premium",
        title = Res.string.pref_section_premium_title,
    ).takeIf { platform.hasSubscription() && !config.isFreeAsBeer },
    SettingsCatalogItem.Action(
        destination = SettingsDestination.SUBSCRIPTION,
        title = Res.string.pref_item_subscription_title,
        text = Res.string.pref_item_subscription_text,
    ).takeIf { platform.hasSubscription() && !config.isFreeAsBeer },
    SettingsCatalogItem.Section(
        id = "section.options",
        title = Res.string.pref_section_options_title,
    ),
    SettingsCatalogItem.Action(
        destination = SettingsDestination.AUTOFILL,
        title = Res.string.pref_item_autofill_title,
        text = Res.string.pref_item_autofill_text,
    ).takeIf { includeAutofill },
    SettingsCatalogItem.Action(
        destination = SettingsDestination.SECURITY,
        title = Res.string.pref_item_security_title,
        text = Res.string.pref_item_security_text,
    ),
    SettingsCatalogItem.Action(
        destination = SettingsDestination.AUTOMATIC_BACKUPS,
        title = Res.string.pref_item_automatic_backups_title,
        text = Res.string.pref_item_automatic_backups_text,
    ).takeIf { includeAutomaticBackups },
    SettingsCatalogItem.Action(
        destination = SettingsDestination.DEVELOPER,
        title = Res.string.pref_item_developer_title,
        text = Res.string.pref_item_developer_text,
    ).takeIf { includeDeveloper },
    SettingsCatalogItem.Action(
        destination = SettingsDestination.WATCHTOWER,
        title = Res.string.pref_item_watchtower_title,
        text = Res.string.pref_item_watchtower_text,
    ).takeIf { includeWatchtower },
    SettingsCatalogItem.Action(
        destination = SettingsDestination.NOTIFICATIONS,
        title = Res.string.pref_item_notifications_title,
        text = Res.string.pref_item_notifications_text,
    ).takeIf { includeNotifications },
    SettingsCatalogItem.Action(
        destination = SettingsDestination.DISPLAY,
        title = Res.string.pref_item_appearance_title,
        text = Res.string.pref_item_appearance_text,
    ),
    SettingsCatalogItem.Action(
        destination = SettingsDestination.DEBUG,
        title = Res.string.pref_item_dev_title,
        text = Res.string.pref_item_dev_text,
    ).takeIf { includeDebug },
    SettingsCatalogItem.Action(
        destination = SettingsDestination.ABOUT,
        title = Res.string.pref_item_other_title,
        text = Res.string.pref_item_other_text,
    ),
)
