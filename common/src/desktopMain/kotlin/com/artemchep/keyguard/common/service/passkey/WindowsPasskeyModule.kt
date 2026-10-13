package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.di.VaultSessionScope
import com.artemchep.keyguard.di.resolveOrCancel
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyBridge
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeys
import kotlinx.coroutines.GlobalScope
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped

class WindowsPasskeyModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<WindowsPasskeyVault>()
        }
        single {
            WindowsPasskeyVaultAccess { session ->
                session.session.resolveOrCancel { get<WindowsPasskeyVault>() }
            }
        }
        single<WindowsPasskeyBridge> { WindowsPasskeys }
        single {
            WindowsPasskeyService(
                getVaultSession = get(),
                vaultAccess = get(),
                vaultSessionLocker = get(),
                authenticator = get(),
                showMessage = get(),
                bridge = get(),
                scope = GlobalScope,
            )
        }
    }
}
