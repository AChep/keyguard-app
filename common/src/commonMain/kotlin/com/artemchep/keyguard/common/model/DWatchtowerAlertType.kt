package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource

enum class DWatchtowerAlertType(
    val value: Long,
    val title: StringResource,
    val level: Level,
    /**
     * `true` if the alert type can be disabled per cipher,
     * `false` otherwise.
     */
    val canBeDisabled: Boolean = true,
) {
    WEAK_PASSWORD(
        value = 3L,
        title = Res.string.watchtower_item_weak_passwords_title,
        level = Level.ERROR,
        canBeDisabled = false,
    ),
    PWNED_PASSWORD(
        value = 4L,
        title = Res.string.watchtower_item_pwned_passwords_title,
        level = Level.ERROR,
    ),
    PWNED_WEBSITE(
        value = 6L,
        title = Res.string.watchtower_item_vulnerable_accounts_title,
        level = Level.ERROR,
    ),
    REUSED_PASSWORD(
        value = 5L,
        title = Res.string.watchtower_item_reused_passwords_title,
        level = Level.ERROR,
    ),
    TWO_FA_WEBSITE(
        value = 1L,
        title = Res.string.watchtower_item_inactive_2fa_title,
        level = Level.WARNING,
    ),
    PASSKEY_WEBSITE(
        value = 2L,
        title = Res.string.watchtower_item_inactive_passkey_title,
        level = Level.INFO,
    ),
    UNSECURE_WEBSITE(
        value = 7L,
        title = Res.string.watchtower_item_unsecure_websites_title,
        level = Level.WARNING,
    ),
    DUPLICATE(
        value = 8L,
        title = Res.string.watchtower_item_duplicate_items_title,
        level = Level.INFO,
    ),
    DUPLICATE_URIS(
        value = 11L,
        title = Res.string.watchtower_item_duplicate_websites_title,
        level = Level.INFO,
    ),
    BROAD_URIS(
        value = 12L,
        title = Res.string.watchtower_item_broad_websites_title,
        level = Level.INFO,
    ),
    INCOMPLETE(
        value = 9L,
        title = Res.string.watchtower_item_incomplete_items_title,
        level = Level.INFO,
    ),
    EXPIRING(
        value = 10L,
        title = Res.string.watchtower_item_expiring_items_title,
        level = Level.INFO,
    );

    companion object {
        fun of(value: Long): DWatchtowerAlertType? =
            DWatchtowerAlertType.entries
                .firstOrNull { it.value == value }
    }

    enum class Level {
        INFO,
        WARNING,
        ERROR,
    }
}
