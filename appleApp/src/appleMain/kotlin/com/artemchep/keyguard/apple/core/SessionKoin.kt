package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.di.resolveOrCancel
import org.koin.core.scope.Scope

/**
 * The unlocked vault's Koin scope. Vault-scoped services (ciphers, folders, sync,
 * filters, ...) resolve from it, and application services fall through to the
 * root graph. Reading it from a retired session cancels the caller, so the
 * bridge observers that run under `launchSessionObserver` simply stop.
 */
internal val VaultState.Main.sessionKoin: Scope
    get() = session.resolveOrCancel { this }

internal val MasterSession.Key.sessionKoin: Scope
    get() = session.resolveOrCancel { this }
