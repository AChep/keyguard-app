package com.artemchep.keyguard.feature.home.settings.component

import kotlinx.coroutines.flow.flowOf
import org.koin.core.scope.Scope

actual fun settingAutofillProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingClipboardNotificationSettingsProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingCredentialProviderProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingEmitTotpProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingPermissionDetailsProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingPermissionCameraProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingPermissionOtherProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingPermissionPostNotificationsProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingPermissionWriteExternalStorageProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingSubscriptionsPlayStoreProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)

actual fun settingYubiKeyUnlockProvider(
    koinScope: Scope,
): SettingComponent = flowOf(null)
