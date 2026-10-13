package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultSessionLocker(
    val getVaultLockAfterTimeout: GetVaultLockAfterTimeout,
    val clearVaultSession: ClearVaultSession,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val DEBOUNCE_MS = 1000L
    }

    private val keepAliveCount = MutableStateFlow(0)
    private val timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
        keepAliveCount
            .map { it > 0 }
            .distinctUntilChanged()
            .collectLatest { active ->
                if (active) return@collectLatest
                // Allow window transitions without starting a new inactivity timeout.
                delay(DEBOUNCE_MS)
                runCatchingNonFatal {
                    // Wait for the timeout duration.
                    delay(getVaultLockAfterTimeout().first())
                    val reason = TextHolder.Res(Res.string.lock_reason_inactivity)
                    clearVaultSession(LockReason.TIMEOUT, reason).bind()
                }
            }
    }

    /** Arm idle locking even when startup never shows a window. Repeated calls are harmless. */
    fun start() {
        timeoutJob.start()
    }

    /** Keep the session alive until the caller is cancelled, then resume idle locking. */
    suspend fun keepAlive() {
        keepAliveCount.update { it + 1 }
        try {
            start()
            awaitCancellation()
        } finally {
            keepAliveCount.update { it - 1 }
        }
    }
}
