package com.artemchep.keyguard.apple.watchtower

import com.artemchep.keyguard.feature.watchtower.WatchtowerState
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot
import com.artemchep.keyguard.res.*

/**
 * Flat, Swift-friendly projection of the Watchtower settings screen, produced by
 * [KeyguardCore.observeWatchtowerSettings]. [hibpCheckState] is one of "checking",
 * "verified", "rejected", "failed" or null (no/blank token).
 */
data class WatchtowerSettingsSnapshot(
    val loaded: Boolean = false,
    val checkPwnedPasswords: Boolean = false,
    val checkPwnedServices: Boolean = false,
    val checkTwoFa: Boolean = false,
    val checkPasskeys: Boolean = false,
    val hibpApiToken: String? = null,
    val hibpCheckState: String? = null,
) {
    companion object {
        val empty = WatchtowerSettingsSnapshot()
    }
}

/**
 * Severity of a watchtower card, mirroring the Compose screen's status icon
 * (a green check when [OK], escalating to [INFO] / [WARNING] / [ERROR] as the
 * card's count crosses its thresholds).
 */
enum class WatchtowerCardStatus {
    OK,
    INFO,
    WARNING,
    ERROR,
}

/**
 * One security / maintenance card of the watchtower dashboard. [id] routes its
 * tap back to [KeyguardCore.invokeWatchtowerAction]; [count] is the headline
 * number, [new] the unread-since-last-visit badge.
 */
data class WatchtowerCardSnapshot(
    val id: String,
    val title: String,
    val text: String,
    val count: Int,
    val new: Int,
    val status: WatchtowerCardStatus,
    val canClick: Boolean,
)

/**
 * One password-strength bucket (Weak / Fair / Good / Strong / VeryStrong) of the
 * dashboard distribution. [score] is the enum name; [id] routes its tap back to
 * [KeyguardCore.invokeWatchtowerAction].
 */
data class WatchtowerStrengthSnapshot(
    val id: String,
    val score: String,
    val count: Int,
    val new: Int,
    val canClick: Boolean,
)

/** A toolbar directory shortcut; [id] routes back to [KeyguardCore.invokeWatchtowerAction]. */
data class WatchtowerOptionSnapshot(
    val id: String,
    val title: String,
)

/**
 * A flat, Swift-friendly projection of the shared [WatchtowerState] for the
 * SwiftUI watchtower dashboard. Built by [KeyguardCore.buildWatchtowerSnapshot].
 */
data class WatchtowerSnapshot(
    val loaded: Boolean,
    val unreadCount: Int,
    val canClickUnread: Boolean,
    val security: List<WatchtowerCardSnapshot>,
    val maintenance: List<WatchtowerCardSnapshot>,
    val strength: List<WatchtowerStrengthSnapshot>,
    val options: List<WatchtowerOptionSnapshot>,
    val filters: List<VaultFilterItemSnapshot>,
    val canClearFilters: Boolean,
    val activeFilterCount: Int,
) {
    companion object {
        val empty = WatchtowerSnapshot(
            loaded = false,
            unreadCount = 0,
            canClickUnread = false,
            security = emptyList(),
            maintenance = emptyList(),
            strength = emptyList(),
            options = emptyList(),
            filters = emptyList(),
            canClearFilters = false,
            activeFilterCount = 0,
        )
    }
}

enum class WatchtowerAlertItemKind {
    SECTION,
    ALERT,
}

/**
 * One row of the watchtower alerts list. For SECTION headers only [title] (the
 * date label) is set; for ALERT rows [title] is the cipher name, [text] the
 * localized alert-type title, [date] the formatted report time and [read] the
 * read flag. [canClick] is true for ALERT rows that open the affected cipher;
 * its tap routes back through [KeyguardCore.invokeWatchtowerAlertItem].
 */
data class WatchtowerAlertItemSnapshot(
    val id: String,
    val kind: WatchtowerAlertItemKind,
    val title: String,
    val text: String,
    val date: String?,
    val read: Boolean,
    val canClick: Boolean = false,
)

/**
 * A flat projection of the watchtower alerts. Built by
 * [KeyguardCore.observeWatchtowerNewAlerts]. [canMarkAllRead] gates the toolbar
 * "mark all as read" button, which routes back through
 * [KeyguardCore.markAllWatchtowerAlertsRead].
 */
data class WatchtowerAlertsSnapshot(
    val loaded: Boolean,
    val items: List<WatchtowerAlertItemSnapshot>,
    val canMarkAllRead: Boolean = false,
) {
    companion object {
        val empty = WatchtowerAlertsSnapshot(loaded = false, items = emptyList())
    }
}

// ---------------------------------------------------------------------------
// About-the-team snapshots (static content).
// ---------------------------------------------------------------------------
