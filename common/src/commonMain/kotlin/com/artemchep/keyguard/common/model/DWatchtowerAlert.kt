package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource

enum class DWatchtowerAlert(
    val title: StringResource,
) {
    PWNED_PASSWORD(
        title = Res.strings.watchtower_item_pwned_passwords_title,
    ),
    PWNED_WEBSITE(
        title = Res.strings.watchtower_item_vulnerable_accounts_title,
    ),
    REUSED_PASSWORD(
        title = Res.strings.watchtower_item_reused_passwords_title,
    ),
    TWO_FA_WEBSITE(
        title = Res.strings.watchtower_item_inactive_2fa_title,
    ),
    PASSKEY_WEBSITE(
        title = Res.strings.watchtower_item_inactive_passkey_title,
    ),
    UNSECURE_WEBSITE(
        title = Res.strings.watchtower_item_unsecure_websites_title,
    ),
    DUPLICATE(
        title = Res.strings.watchtower_item_duplicate_items_title,
    ),
    INCOMPLETE(
        title = Res.strings.watchtower_item_incomplete_items_title,
    ),
    EXPIRING(
        title = Res.strings.watchtower_item_expiring_items_title,
    ),
}
