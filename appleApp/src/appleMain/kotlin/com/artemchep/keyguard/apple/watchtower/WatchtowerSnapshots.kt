package com.artemchep.keyguard.apple.watchtower

import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot

/** [hibpCheckState] is one of "checking", "verified", "rejected", "failed" or null (no / blank token). */
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
 * Mirrors the Compose screen's status icon: a green check when [OK], escalating to [INFO] / [WARNING] / [ERROR]
 * as the card's count crosses its thresholds.
 */
enum class WatchtowerCardStatus {
    OK,
    INFO,
    WARNING,
    ERROR,
}

/**
 * [id] routes its tap back to [WatchtowerSession.invokeWatchtowerAction]; [new] is the unread-since-last-visit badge.
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
 * [score] is the `PasswordStrength.Score` name (Weak / Fair / Good / Strong / VeryStrong); [id] routes its tap
 * back to [WatchtowerSession.invokeWatchtowerAction].
 */
data class WatchtowerStrengthSnapshot(
    val id: String,
    val score: String,
    val count: Int,
    val new: Int,
    val canClick: Boolean,
)

/** A toolbar directory shortcut; [id] routes back to [WatchtowerSession.invokeWatchtowerAction]. */
data class WatchtowerOptionSnapshot(
    val id: String,
    val title: String,
)

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
 * For SECTION headers only [title] (the date label) is set; for ALERT rows [title] is the cipher name, [text] the
 * localized alert-type title and [date] the formatted report time. [canClick] is true for ALERT rows that open the
 * affected cipher; the tap passes [id] to [KeyguardCore.invokeEntryAction].
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
 * [canMarkAllRead] gates the toolbar "mark all as read" button, which passes the fixed id "markAllRead" to
 * [KeyguardCore.invokeEntryAction].
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
