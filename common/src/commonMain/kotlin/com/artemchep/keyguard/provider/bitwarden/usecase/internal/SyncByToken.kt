package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import org.kodein.di.DirectDI
import org.kodein.di.instance

interface SyncByToken : (ServiceToken) -> IO<Unit>

class SyncByTokenImpl(
    private val syncByBitwardenToken: SyncByBitwardenToken,
    private val syncByKeePassToken: SyncByKeePassToken,
    syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SyncByToken {
    private val scheduler = AccountSyncScheduler(
        scope = syncScope,
        sync = ::syncNow,
    )

    constructor(directDI: DirectDI) : this(
        syncByBitwardenToken = directDI.instance(),
        syncByKeePassToken = directDI.instance(),
    )

    override fun invoke(token: ServiceToken): IO<Unit> = scheduler.enqueue(token)

    private fun syncNow(token: ServiceToken): IO<Unit> = when (token) {
        is BitwardenToken -> syncByBitwardenToken(token)
        is KeePassToken -> syncByKeePassToken(token)
    }
}

interface SyncByBitwardenToken : (BitwardenToken) -> IO<Unit>

interface SyncByKeePassToken : (KeePassToken) -> IO<Unit>
