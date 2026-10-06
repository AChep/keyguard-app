package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.apple.model.SettingOptionSnapshot

enum class SettingsItemKind {
    /** A non-selectable group header (e.g. "Options"). */
    SECTION,

    /** A selectable row that opens a settings sub-route. */
    ACTION,
}

data class SettingsItemSnapshot(
    val id: String,
    val kind: SettingsItemKind,
    val title: String,
    val text: String?,
)
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
 * [lockAfterRebootVisible] mirrors the common rule that the lock-after-reboot toggle only applies when the vault
 * persists to disk.
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
 * [canConfirm] is true only when both fields are valid and non-empty.
 *
 * [biometricVisible] mirrors the shared producer's `state.biometric != null` rule: the "Use biometric
 * authentication" re-enroll checkbox shows only when the account has biometric unlock enabled. When it is checked,
 * confirming shows a biometric prompt through the shared native prompt host before the password changes.
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

/** An option id is the variant's name (the locale tag for locales), or "system" for the follow-system entry. */
data class AppearanceSettingsSnapshot(
    val loaded: Boolean = false,
    val amoledDark: Boolean = false,
    val expressive: Boolean = false,
    val markdown: Boolean = false,
    val navLabel: Boolean = false,
    val useExternalBrowser: Boolean = false,
    val keepScreenOn: Boolean = false,
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

/** Preferences the SwiftUI shell applies app-wide. Observed for the app's whole lifetime, not per screen. */
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
    /** True when closing the last window keeps the app running in the menu bar; false quits the app. */
    val closeToTray: Boolean = false,
    /** Keeps supported iOS detail views awake while foreground and unlocked. */
    val keepScreenOn: Boolean = false,
    /** Opens ordinary web links with the system instead of the in-app browser. */
    val useExternalBrowser: Boolean = false,
    /** True when WebDAV writes go through a temporary upload followed by MOVE. */
    val webDavTransactions: Boolean = true,
) {
    companion object {
        val empty = AppPreferencesSnapshot()
    }
}

/** Saved backup configuration and run status, shared by settings presentations. */
data class BackupSettingsSnapshot(
    val loaded: Boolean = false,
    val initializationFailed: Boolean = false,
    val enabled: Boolean = false,
    val storeKind: String = "local",
    val localPath: String? = null,
    val webDavUrl: String? = null,
    val webDavUsername: String? = null,
    /** The S3 destination as `s3://bucket/prefix`. */
    val s3Location: String? = null,
    /** The host of a custom S3 endpoint, or null for Amazon S3. */
    val s3EndpointHost: String? = null,
    val hasPassword: Boolean = false,
    val includeAttachments: Boolean = true,
    val retentionMaxSnapshots: Int = 30,
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
 * macOS launch-at-login state. [requiresApproval]: registered but awaiting the user's approval in
 * System Settings ▸ Login Items. [available]: the underlying service could be queried at all.
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

/**
 * The Apple-applicable items of the common `AutofillSettingsScreen`. The Android-only items (inline suggestions,
 * manual selection, respect autofill-off, credential-provider registration) are not surfaced.
 */
data class AutofillSettingsSnapshot(
    val loaded: Boolean = false,
    val copyTotp: Boolean = false,
    val saveRequest: Boolean = false,
    val saveUri: Boolean = false,
    val defaultMatchDetectionTitle: String = "",
    val defaultMatchDetectionOptions: List<SettingOptionSnapshot> = emptyList(),
) {
    companion object {
        val empty = AutofillSettingsSnapshot()
    }
}

data class AboutTeamSocialSnapshot(
    val title: String,
    val username: String,
    val url: String,
)

data class AboutTeamSnapshot(
    val name: String,
    val flag: String,
    val about: String,
    val socialNetworks: List<AboutTeamSocialSnapshot>,
)

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
