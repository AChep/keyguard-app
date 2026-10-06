package com.artemchep.keyguard.feature.home.settings.component

import org.koin.core.scope.Scope

actual fun settingYubiKeyUnlockProvider(
    koinScope: Scope,
): SettingComponent = settingYubiKeyUnlockProvider(
    fingerprintReadRepository = koinScope.get(),
    getVaultSession = koinScope.get(),
    enableYubiKeyUnlock = koinScope.get(),
    disableYubiKeyUnlock = koinScope.get(),
    cryptoGenerator = koinScope.get(),
    showMessage = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

