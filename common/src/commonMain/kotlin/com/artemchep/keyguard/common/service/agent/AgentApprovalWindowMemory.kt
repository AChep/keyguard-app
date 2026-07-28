package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
 * for GPG). [P] is the policy used to derive that identity. A request obtains
 * one [Access] from [Session.access] and keeps it through the approval prompt;
 * this binds lookup and remember to the same policy epoch. If policy, window,
 * or vault state changes meanwhile, [Access.remember] is a no-op.
 */
class AgentApprovalWindowMemory<K : Any, P : Any>(
    private val approvalCacheConfig: AgentApprovalCacheConfigProvider<P>,
    getVaultSession: GetVaultSession,
    scope: CoroutineScope,
    maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    companion object {
        private const val DEFAULT_MAX_CACHE_ENTRIES = 256
    }

    private val maxCacheEntries = maxCacheEntries.also {
        require(it > 0) { "maxCacheEntries must be positive." }
    }
    private val mutex = Mutex()
    private val cache = linkedMapOf<CacheKey<K>, ApprovalCacheEntry>()
    private var activeSession: ActiveSession? = null
    private var sessionGeneration = 0L
    private var activeConfigRevision: Long? = null
    private var cacheEpoch = 0L

    init {
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
            invalidateCacheLocked()
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
        invalidateCacheLocked()
        Session(generation = generation)
    }

    private fun invalidateCacheLocked() {
        cache.clear()
        cacheEpoch += 1L
    }

    private suspend fun getApprovalCacheConfigLocked(): AgentApprovalCacheConfig<P> {
        val config = approvalCacheConfig.get()
        if (activeConfigRevision != config.revision) {
            activeConfigRevision = config.revision
            invalidateCacheLocked()
        }
        return config
    }

    private suspend fun access(
        session: Session,
        keyForPolicy: (P) -> K?,
    ): Access = mutex.withLock {
        val config = getApprovalCacheConfigLocked()
        val approvalWindow = config.approvalWindow
        val epoch = cacheEpoch
        val key = config.cachePolicy.let(keyForPolicy)
        val isActiveSession = activeSession?.generation == session.generation
        if (approvalWindow <= Duration.ZERO || !isActiveSession || key == null) {
            return@withLock Access(
                sessionGeneration = session.generation,
                cacheEpoch = epoch,
                configRevision = config.revision,
                approvalWindow = approvalWindow,
                key = key,
                isRemembered = false,
            )
        }

        val cacheKey = CacheKey(session.generation, key)
        val isRemembered = run {
            // Removing and re-inserting a live entry refreshes its position in
            // the insertion-ordered map, giving us simple LRU eviction.
            val entry = cache.remove(cacheKey)
                ?: return@run false
            if (entry.approvalWindow != approvalWindow || entry.isExpired()) {
                false
            } else {
                cache[cacheKey] = entry
                true
            }
        }
        Access(
            sessionGeneration = session.generation,
            cacheEpoch = epoch,
            configRevision = config.revision,
            approvalWindow = approvalWindow,
            key = key,
            isRemembered = isRemembered,
        )
    }

    private suspend fun remember(
        access: Access,
    ) {
        mutex.withLock {
            val config = getApprovalCacheConfigLocked()
            val currentApprovalWindow = config.approvalWindow
            if (
                config.revision != access.configRevision ||
                currentApprovalWindow != access.approvalWindow ||
                currentApprovalWindow <= Duration.ZERO ||
                cacheEpoch != access.cacheEpoch ||
                activeSession?.generation != access.sessionGeneration
            ) {
                return
            }

            val key = access.key ?: return
            val cacheKey = CacheKey(access.sessionGeneration, key)
            pruneExpiredEntriesLocked(currentApprovalWindow)
            val entry = ApprovalCacheEntry(
                approvalWindow = currentApprovalWindow,
                expiresAt = currentApprovalWindow
                    .takeUnless { it == Duration.INFINITE }
                    ?.let { timeSource.markNow() + it },
            )

            cache.remove(cacheKey)
            cache[cacheKey] = entry
            while (cache.size > maxCacheEntries) {
                val leastRecentlyUsedKey = cache.keys.first()
                cache.remove(leastRecentlyUsedKey)
            }
        }
    }

    private fun pruneExpiredEntriesLocked(
        approvalWindow: Duration,
    ) {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.approvalWindow != approvalWindow || entry.isExpired()) {
                iterator.remove()
            }
        }
    }

    inner class Session internal constructor(
        val generation: Long,
    ) {
        suspend fun access(
            keyForPolicy: (P) -> K?,
        ): Access = this@AgentApprovalWindowMemory.access(this, keyForPolicy)
    }

    inner class Access internal constructor(
        internal val sessionGeneration: Long,
        internal val cacheEpoch: Long,
        internal val configRevision: Long,
        internal val approvalWindow: Duration,
        internal val key: K?,
        val isRemembered: Boolean,
    ) {
        suspend fun remember() {
            this@AgentApprovalWindowMemory.remember(this)
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
