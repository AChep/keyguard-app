package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.res.*
import kotlin.time.Duration
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

enum class SettingsItemKind {
    /** A non-selectable group header (e.g. "Options"). */
    SECTION,

    /** A selectable row that opens a settings sub-route. */
    ACTION,
}

/** One row of the settings list — either a section header or a navigable action. */
data class SettingsItemSnapshot(
    val id: String,
    val kind: SettingsItemKind,
    val title: String,
    val text: String?,
)
/**
 * A flat, Swift-friendly projection of the shared settings catalog. Built by
 * [KeyguardCore.loadSettingsList].
 */
data class SettingsListSnapshot(
    val items: List<SettingsItemSnapshot>,
) {
    companion object {
        val empty = SettingsListSnapshot(items = emptyList())
    }
}

data class DebugSettingsSnapshot(
    val loaded: Boolean = false,
    val premiumOverrideAvailable: Boolean = false,
    val premiumOverrideEnabled: Boolean = false,
) {
    companion object {
        val empty = DebugSettingsSnapshot()
    }
}

/**
 * Flat, Swift-friendly projection of the Security settings screen, produced by
 * [KeyguardCore.observeSecuritySettings]. Duration pickers are surfaced as
 * [SettingOptionSnapshot] lists; the selected option's display string is also
 * provided directly. [lockAfterRebootVisible] mirrors the common rule that the
 * lock-after-reboot toggle only applies when the vault persists to disk.
 */
data class SecuritySettingsSnapshot(
    val loaded: Boolean = false,
    val vaultPersist: Boolean = false,
    val lockAfterRebootVisible: Boolean = false,
    val lockAfterReboot: Boolean = false,
    val lockTimeoutTitle: String = "",
    val lockTimeoutOptions: List<SettingOptionSnapshot> = emptyList(),
    val clipboardAutoClearTitle: String = "",
    val clipboardAutoClearOptions: List<SettingOptionSnapshot> = emptyList(),
    val conceal: Boolean = false,
    val websiteIcons: Boolean = false,
    val gravatar: Boolean = false,
    val biometricUnlockSupported: Boolean = false,
    val biometricUnlockEnabled: Boolean = false,
    val biometricTimeoutTitle: String = "",
    val biometricTimeoutOptions: List<SettingOptionSnapshot> = emptyList(),
    val yubiKeyUnlockSupported: Boolean = false,
    val yubiKeyUnlockEnabled: Boolean = false,
    val fido2UnlockSupported: Boolean = false,
    val fido2UnlockEnabled: Boolean = false,
) {
    companion object {
        val empty = SecuritySettingsSnapshot()
    }
}

/**
 * Flat, Swift-friendly projection of the change-master-password screen, produced
 * by [KeyguardCore.observeChangePassword]. [canConfirm] is true only when both
 * fields are valid and non-empty.
 *
 * [biometricVisible] mirrors the shared producer's `state.biometric != null` rule:
 * the "Use biometric authentication" re-enroll checkbox is shown only when the
 * account currently has biometric unlock enabled. When the box is checked, the
 * producer routes the confirm through the biometric path, which emits a
 * [com.artemchep.keyguard.common.model.BiometricAuthPrompt] that the controller
 * delivers through the shared native prompt host before the password is changed.
 */
data class ChangePasswordSnapshot(
    val loaded: Boolean = false,
    val currentPassword: String = "",
    val currentError: String? = null,
    val newPassword: String = "",
    val newError: String? = null,
    val biometricVisible: Boolean = false,
    val biometricChecked: Boolean = false,
    val canConfirm: Boolean = false,
    val isLoading: Boolean = false,
) {
    companion object {
        val empty = ChangePasswordSnapshot()
    }
}

/**
 * Flat, Swift-friendly projection of the Appearance settings screen, produced by
 * [KeyguardCore.observeAppearanceSettings]. Enum pickers carry their options as
 * [SettingOptionSnapshot] lists (the option id is the variant's list index) plus
 * the selected option's display title.
 */
data class AppearanceSettingsSnapshot(
    val loaded: Boolean = false,
    val amoledDark: Boolean = false,
    val expressive: Boolean = false,
    val markdown: Boolean = false,
    val navLabel: Boolean = false,
    val useExternalBrowser: Boolean = false,
    val minimizeOnCopy: Boolean = false,
    val closeToTray: Boolean = false,
    val websiteIcons: Boolean = false,
    val gravatar: Boolean = false,
    val twoPanelPortrait: Boolean = false,
    val twoPanelLandscape: Boolean = false,
    val themeTitle: String = "",
    val themeOptions: List<SettingOptionSnapshot> = emptyList(),
    val accentTitle: String = "",
    val accentOptions: List<SettingOptionSnapshot> = emptyList(),
    val fontTitle: String = "",
    val fontOptions: List<SettingOptionSnapshot> = emptyList(),
    val navAnimationTitle: String = "",
    val navAnimationOptions: List<SettingOptionSnapshot> = emptyList(),
    val localeTitle: String = "",
    val localeOptions: List<SettingOptionSnapshot> = emptyList(),
) {
    companion object {
        val empty = AppearanceSettingsSnapshot()
    }
}

/**
 * The preferences the SwiftUI shell applies app-wide, produced by
 * [KeyguardCore.observeAppPreferences]. Observed for the app's whole lifetime
 * (unlike the per-screen settings snapshots).
 */
data class AppPreferencesSnapshot(
    /** BCP-47 override, or null to follow the system language. */
    val locale: String? = null,
    val loaded: Boolean = false,
    /** "dark" / "light", or `null` to follow the system appearance. */
    val theme: String? = null,
    /** The accent color as 0xAARRGGBB, or `null` to use the system accent. */
    val accentArgb: Long? = null,
    /** True when navigation tab/rail labels should be shown. */
    val navLabel: Boolean = true,
    /**
     * True when closing the last window should keep the app running in the
     * menu bar; false quits the app instead.
     */
    val closeToTray: Boolean = false,
) {
    companion object {
        val empty = AppPreferencesSnapshot()
    }
}

/**
 * Flat, Swift-friendly projection of the Automatic Backups screen, produced by
 * [KeyguardCore.observeBackupSettings]. `…` (no prefix) fields are the saved
 * config; `setup…` fields are the in-memory editing buffer surfaced for the form.
 * [storeKind] / [setupStoreKind] are "local" or "webdav".
 */
data class BackupSettingsSnapshot(
    val loaded: Boolean = false,
    val initializationFailed: Boolean = false,
    val enabled: Boolean = false,
    val isTestingLocation: Boolean = false,
    val setupError: String? = null,
    val storeKind: String = "local",
    val localPath: String? = null,
    val webDavUrl: String? = null,
    val webDavUsername: String? = null,
    val hasPassword: Boolean = false,
    val includeAttachments: Boolean = true,
    val retentionMaxSnapshots: Int = 30,
    val setupStoreKind: String = "local",
    val setupLocalPath: String? = null,
    val setupWebDavUrl: String? = null,
    val setupWebDavUsername: String? = null,
    val setupHasWebDavPassword: Boolean = false,
    val setupHasPassword: Boolean = false,
    val setupIncludeAttachments: Boolean = true,
    val setupRetentionMaxSnapshots: Int = 30,
    val setupSaveRevision: Long = 0,
    val lastSuccessfulBackupAtMs: Long? = null,
    val lastErrorMessage: String? = null,
    val isDirty: Boolean = false,
    val runningStep: String? = null,
    val isRunning: Boolean = false,
) {
    companion object {
        val empty = BackupSettingsSnapshot()
    }
}

/**
 * A flat projection of the macOS launch-at-login state. Built by
 * [KeyguardCore.observeLaunchAtLogin].
 *  - [enabled]: the app is registered as a login item,
 *  - [requiresApproval]: registered but awaiting the user's approval in
 *    System Settings ▸ Login Items,
 *  - [available]: the underlying service could be queried at all.
 */
data class LaunchAtLoginSnapshot(
    val enabled: Boolean,
    val requiresApproval: Boolean,
    val available: Boolean,
) {
    companion object {
        val empty = LaunchAtLoginSnapshot(
            enabled = false,
            requiresApproval = false,
            available = false,
        )
    }
}

// ---------------------------------------------------------------------------
// AutoFill snapshots.
// ---------------------------------------------------------------------------

/**
 * Flat, Swift-friendly projection of the Apple-applicable AutoFill toggles,
 * produced by [KeyguardCore.observeAutofillSettings]. Mirrors the macOS/iOS-
 * relevant items of the common `AutofillSettingsScreen`: copy-TOTP-to-clipboard,
 * save-credential prompts, and save-URI-to-existing-item prompts. The Android-
 * only items (inline suggestions, manual selection, respect autofill-off,
 * default match detection, credential-provider registration) are not surfaced.
 */
data class AutofillSettingsSnapshot(
    val loaded: Boolean = false,
    val copyTotp: Boolean = false,
    val saveRequest: Boolean = false,
    val saveUri: Boolean = false,
) {
    companion object {
        val empty = AutofillSettingsSnapshot()
    }
}

/** One social link on the About-the-team screen. */
data class AboutTeamSocialSnapshot(
    val title: String,
    val username: String,
    val url: String,
)

/** The static About-the-team content. Built by [KeyguardCore.loadAboutTeam]. */
data class AboutTeamSnapshot(
    val name: String,
    val flag: String,
    val about: String,
    val socialNetworks: List<AboutTeamSocialSnapshot>,
)

// ---------------------------------------------------------------------------
// Data safety snapshots (static document).
// ---------------------------------------------------------------------------

enum class DataSafetyItemKind {
    LARGE_SECTION,
    SECTION,
    TEXT,
    ROW,
    LEARN_MORE,
}

/**
 * One item of the Data Safety document. Field relevance depends on [kind]:
 * LARGE_SECTION / SECTION / TEXT use [text]; ROW uses [title] + [value];
 * LEARN_MORE uses [text] (button label) + [url]. [secondary] dims TEXT/ROW items.
 */
data class DataSafetyItemSnapshot(
    val id: String,
    val kind: DataSafetyItemKind,
    val text: String,
    val title: String,
    val value: String,
    val secondary: Boolean,
    val url: String?,
)
