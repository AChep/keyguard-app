package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionAccess
import com.artemchep.keyguard.common.service.session.BrowserExtensionSessionDependencies
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserVaultState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The vault, as the sessions of the extensions see it. App-scoped. */
internal class BrowserVaultAccess(
    private val getVaultSession: GetVaultSession,
    private val sessionAccess: BrowserExtensionSessionAccess,
) {
    /** The state of the vault, as the extensions see it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<BrowserVaultState> = getVaultSession()
        .flatMapLatest { session ->
            if (session is MasterSession.Key) session.session.active else flowOf(false)
        }
        .map(::vaultStateOf)
        .distinctUntilChanged()

    /** What the operations read in the vault, or `null` while it is locked. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val unlocked: Flow<BrowserExtensionSessionDependencies?> = getVaultSession()
        .flatMapLatest { session ->
            if (session is MasterSession.Key) {
                session.session.active.map { active -> if (active) sessionAccess(session) else null }
            } else {
                flowOf(null)
            }
        }

    /** The state of the vault now. */
    fun currentState(): BrowserVaultState = vaultStateOf(currentKey() != null)

    /** The unlocked vault session, or `null` while it is locked. */
    fun currentKey(): MasterSession.Key? =
        (getVaultSession.valueOrNull as? MasterSession.Key)
            ?.takeIf { it.session.active.value }

    /** What the operations read in the vault, or `null` while it is locked. */
    fun dependencies(): BrowserExtensionSessionDependencies? = currentKey()
        ?.let(sessionAccess::invoke)
}

/**
 * Unlocked while the vault session is [active]: its dependencies resolve
 * until it retires, which it does before its scope closes.
 */
private fun vaultStateOf(active: Boolean): BrowserVaultState =
    if (active) BrowserVaultState.UNLOCKED else BrowserVaultState.LOCKED
