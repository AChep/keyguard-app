package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Remembers granted signing approvals for the duration of the user's
 * "Remember key approvals" window, so repeated signatures with the same key
 * and caller do not re-prompt. Approvals are scoped to the currently unlocked
 * vault session and invalidated when the window setting changes, when it is
 * set to zero, or when the vault locks.
 */
class SshAgentApprovalWindowMemory(
    getSshAgentApprovalWindow: GetSshAgentApprovalWindow,
    getVaultSession: GetVaultSession,
    scope: CoroutineScope,
) {
    private val approvalWindowState = getSshAgentApprovalWindow()
        .stateIn(scope, SharingStarted.Eagerly, Duration.ZERO)

    private val mutex = Mutex()
    private val cache = mutableMapOf<ApprovalCacheKey, ApprovalCacheEntry>()
    private var activeSession: ActiveSession? = null
    private var sessionGeneration = 0L

    init {
        // Changing the window invalidates approvals granted under older rules.
        var previousApprovalWindow = approvalWindowState.value
        approvalWindowState
            .onEach { approvalWindow ->
                val hasChanged = approvalWindow != previousApprovalWindow
                previousApprovalWindow = approvalWindow

                if (hasChanged || approvalWindow <= Duration.ZERO) {
                    clearCache()
                }
            }
            .launchIn(scope)

        // A remembered approval belongs only to the currently unlocked vault.
        getVaultSession()
            .onEach { session ->
                val key = session as? MasterSession.Key
                if (key != null) {
                    getOrGenerateSession(key)
                } else {
                    clearSession()
                }
            }
            .launchIn(scope)
    }

    suspend fun clearSession() {
        mutex.withLock {
            activeSession = null
            cache.clear()
        }
    }

    suspend fun getOrGenerateSession(
        session: MasterSession.Key,
    ): Session = mutex.withLock {
        activeSession
            ?.takeIf { it.session === session }
            ?.let { return@withLock Session(generation = it.generation) }

        // Use identity equality above: a new MasterSession.Key means a new unlock.
        val generation = sessionGeneration + 1L
        sessionGeneration = generation
        activeSession = ActiveSession(
            session = session,
            generation = generation,
        )
        cache.clear()
        Session(generation = generation)
    }

    private suspend fun clearCache() {
        mutex.withLock {
            cache.clear()
        }
    }

    private suspend fun isRemembered(
        session: Session,
        request: SshAgentMessages.SignDataRequest,
    ): Boolean {
        val approvalWindow = approvalWindowState.value
        if (approvalWindow <= Duration.ZERO) {
            return false
        }

        val key = request.toApprovalCacheKey(session)
        return mutex.withLock {
            val entry = cache[key]
                ?: return@withLock false

            if (entry.approvalWindow != approvalWindow || entry.isExpired()) {
                cache.remove(key)
                false
            } else {
                true
            }
        }
    }

    private suspend fun remember(
        session: Session,
        request: SshAgentMessages.SignDataRequest,
    ) {
        val approvalWindow = approvalWindowState.value
        if (approvalWindow <= Duration.ZERO) {
            return
        }

        val key = request.toApprovalCacheKey(session)
        val entry = ApprovalCacheEntry(
            approvalWindow = approvalWindow,
            expiresAt = approvalWindow
                .takeUnless { it == Duration.INFINITE }
                ?.let { TimeSource.Monotonic.markNow() + it },
        )
        mutex.withLock {
            // Re-check after suspension so stale approvals are not written back.
            val currentApprovalWindow = approvalWindowState.value
            if (currentApprovalWindow != approvalWindow || currentApprovalWindow <= Duration.ZERO) {
                return
            }

            if (activeSession?.generation != session.generation) {
                return
            }

            cache[key] = entry
        }
    }

    private fun SshAgentMessages.SignDataRequest.toApprovalCacheKey(
        session: Session,
    ): ApprovalCacheKey {
        val publicKeyToken = decodeSshPublicKeyBlob(publicKey)
            ?.toHex()
            ?: publicKey.trim()
        return ApprovalCacheKey(
            sessionGeneration = session.generation,
            publicKeyToken = publicKeyToken,
            callerToken = caller.toApprovalCacheToken(),
        )
    }

    private fun SshAgentMessages.CallerIdentity?.toApprovalCacheToken(): String {
        // At this moment we are not precise at all with the caller
        // identity, so for now just use a generic name of the app.
        return "generic-caller=${this?.appName.orEmpty()}"
    }

    inner class Session internal constructor(
        val generation: Long,
    ) {
        suspend fun isRemembered(
            request: SshAgentMessages.SignDataRequest,
        ): Boolean = this@SshAgentApprovalWindowMemory.isRemembered(this, request)

        suspend fun remember(
            request: SshAgentMessages.SignDataRequest,
        ) {
            this@SshAgentApprovalWindowMemory.remember(this, request)
        }
    }

    private data class ActiveSession(
        val session: MasterSession.Key,
        val generation: Long,
    )

    private data class ApprovalCacheKey(
        val sessionGeneration: Long,
        val publicKeyToken: String,
        val callerToken: String,
    )

    private data class ApprovalCacheEntry(
        val approvalWindow: Duration,
        val expiresAt: TimeMark?,
    ) {
        fun isExpired(): Boolean = expiresAt?.hasPassedNow() == true
    }
}
