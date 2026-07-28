package com.artemchep.keyguard.feature.home.settings.component

import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI

actual fun settingAutofillProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingClipboardNotificationSettingsProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingCredentialProviderProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingEmitTotpProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingPermissionDetailsProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingPermissionCameraProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingPermissionOtherProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingPermissionPostNotificationsProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingPermissionWriteExternalStorageProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingSubscriptionsPlayStoreProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)

actual fun settingYubiKeyUnlockProvider(
    directDI: DirectDI,
): SettingComponent = flowOf(null)
