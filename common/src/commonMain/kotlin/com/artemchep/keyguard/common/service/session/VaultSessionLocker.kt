package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.suspendCancellableCoroutine
import org.kodein.di.DirectDI
import org.kodein.di.instance

class VaultSessionLocker(
    val getVaultLockAfterTimeout: GetVaultLockAfterTimeout,
    val putVaultSession: PutVaultSession,
    private val scope: CoroutineScope,
    private val context: LeContext,
) {
    companion object {
        private const val DEBOUNCE_MS = 1000L 
    }
    
    private var clearVaultSessionJob: Job? = null

    /**
     * A flow that keeps the vault session alive. Once the flow is not active anymore,
     * the clear vault session job spawns.
     */
    private val keepAliveFlow = flow<Unit> {
        clearVaultSessionJob?.cancel()
        clearVaultSessionJob = null

        try {
            // suspend forever
            suspendCancellableCoroutine<Unit> { }
        } finally {
            clearVaultSessionJob = getVaultLockAfterTimeout()
                .toIO()
                // Wait for the timeout duration.
                .effectMap { duration ->
                    delay(duration)
                    duration
                }
                .effectMap {
                    // Clear the current session.
                    val session = MasterSession.Empty(
                        reason = textResource(Res.string.lock_reason_inactivity, context),
                    )
                    putVaultSession(session)
                }
                .flatten()
                .attempt()
                .launchIn(scope)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(DEBOUNCE_MS))

    constructor(directDI: DirectDI) : this(
        getVaultLockAfterTimeout = directDI.instance(),
        putVaultSession = directDI.instance(),
        scope = GlobalScope,
        context = directDI.instance(),
    )

    suspend fun keepAlive() {
        keepAliveFlow.collect()
    }
}
