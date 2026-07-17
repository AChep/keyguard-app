package com.artemchep.keyguard.feature.home.vault.screen

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

internal enum class WriteCapability {
    Unknown,
    Allowed,
    Denied,
}

/**
 * Combines account existence and repository permission without making account
 * discovery part of the vault-content readiness barrier.
 *
 * The initial state is [WriteCapability.Unknown]. A confirmed no-account state
 * resolves to [WriteCapability.Denied] because there is no account to mutate.
 */
internal fun vaultListWriteCapabilityFlow(
    hasAccountsFlow: Flow<Boolean>,
    capabilityFlow: Flow<WriteCapability>,
): Flow<WriteCapability> {
    val accountAvailabilityFlow: Flow<Boolean?> = hasAccountsFlow
        .map<Boolean, Boolean?> { hasAccounts ->
            hasAccounts
        }
        .onStart {
            emit(null)
        }
    return combine(
        accountAvailabilityFlow,
        capabilityFlow,
    ) { hasAccounts, capability ->
        when (hasAccounts) {
            null -> WriteCapability.Unknown
            false -> WriteCapability.Denied
            true -> capability
        }
    }
        .distinctUntilChanged()
}
