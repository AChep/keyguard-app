package com.artemchep.keyguard.feature.home.settings.component

import org.koin.core.scope.Scope
import com.artemchep.keyguard.util.yubikey.NativeYubiKeyClient
import kotlinx.coroutines.flow.flowOf

actual fun settingYubiKeyUnlockProvider(
    koinScope: Scope,
): SettingComponent = if (!NativeYubiKeyClient().isSupported) flowOf(null) else settingYubiKeyUnlockProvider(
    fingerprintReadRepository = koinScope.get(),
    getVaultSession = koinScope.get(),
    enableYubiKeyUnlock = koinScope.get(),
    disableYubiKeyUnlock = koinScope.get(),
    cryptoGenerator = koinScope.get(),
    showMessage = koinScope.get(),
    windowCoroutineScope = koinScope.get(),
)

