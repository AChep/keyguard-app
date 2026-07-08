package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
 * Remembers granted approvals for the duration of the user's approval window,
 * so repeated operations with the same key do not re-prompt. Approvals are
 * scoped to the currently unlocked vault session and invalidated when the
 * window setting changes, when it is set to zero, or when the vault locks.
 *
 * The [K] type parameter is the agent-specific approval identity (for example,
 * the public-key token and caller for SSH, or the operation, keygrip and caller
 * for GPG). Callers derive that identity and pass it to [Session.isRemembered]
 * and [Session.remember]; this class combines it with the vault session
 * generation to form the actual cache key.
 */
class AgentApprovalWindowMemory<K : Any>(
    approvalWindow: Flow<Duration>,
    getVaultSession: GetVaultSession,
    scope: CoroutineScope,
) {
    private val approvalWindowState = approvalWindow
        .stateIn(scope, SharingStarted.Eagerly, Duration.ZERO)

    private val mutex = Mutex()
    private val cache = mutableMapOf<CacheKey<K>, ApprovalCacheEntry>()
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
        key: K,
    ): Boolean {
        val approvalWindow = approvalWindowState.value
        if (approvalWindow <= Duration.ZERO) {
            return false
        }

        val cacheKey = CacheKey(
            sessionGeneration = session.generation,
            key = key,
        )
        return mutex.withLock {
            val entry = cache[cacheKey]
                ?: return@withLock false

            if (entry.approvalWindow != approvalWindow || entry.isExpired()) {
                cache.remove(cacheKey)
                false
            } else {
                true
            }
        }
    }

    private suspend fun remember(
        session: Session,
        key: K,
    ) {
        val approvalWindow = approvalWindowState.value
        if (approvalWindow <= Duration.ZERO) {
            return
        }

        val cacheKey = CacheKey(
            sessionGeneration = session.generation,
            key = key,
        )
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

            cache[cacheKey] = entry
        }
    }

    inner class Session internal constructor(
        val generation: Long,
    ) {
        suspend fun isRemembered(
            key: K,
        ): Boolean = this@AgentApprovalWindowMemory.isRemembered(this, key)

        suspend fun remember(
            key: K,
        ) {
            this@AgentApprovalWindowMemory.remember(this, key)
        }
    }

    private data class ActiveSession(
        val session: MasterSession.Key,
        val generation: Long,
    )

    private data class CacheKey<K : Any>(
        val sessionGeneration: Long,
        val key: K,
    )

    private data class ApprovalCacheEntry(
        val approvalWindow: Duration,
        val expiresAt: TimeMark?,
    ) {
        fun isExpired(): Boolean = expiresAt?.hasPassedNow() == true
    }
}
