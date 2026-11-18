package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Runs [block] of a passkey request for the vault [session], and keeps the vault from
 * locking for inactivity meanwhile. The session is not checked on entry: [block]
 * checks it before it acts on the vault.
 *
 * Once the session retires, [onRetired] runs and then [block] is cancelled, so this
 * call throws a `CancellationException` while the caller stays active. A
 * `CancellationException` from [onRetired] skips that cancellation; any other
 * exception from it fails this call.
 */
internal suspend fun <T> VaultSessionLocker.withSessionLease(
    session: MasterSession.Key,
    onRetired: suspend () -> Unit = {},
    block: suspend () -> T,
): T = coroutineScope {
    val operationScope = this
    // Cancellation releases this lease at once.
    val keepAlive = launch(start = CoroutineStart.UNDISPATCHED) {
        keepAlive()
    }
    val retired = launch {
        session.session.active.first { !it }
        onRetired()
        operationScope.cancel("Vault session retired")
    }
    try {
        block()
    } finally {
        keepAlive.cancel()
        retired.cancel()
    }
}
