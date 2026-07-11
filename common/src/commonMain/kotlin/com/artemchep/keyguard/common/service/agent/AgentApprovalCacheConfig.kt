package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.io.IO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

data class AgentApprovalCacheConfig<P : Any>(
    val approvalWindow: Duration,
    val cachePolicy: P,
    val revision: Long,
)

fun interface AgentApprovalCacheConfigProvider<P : Any> {
    suspend fun get(): AgentApprovalCacheConfig<P>
}

/**
 * Owns the authoritative in-process approval-cache configuration.
 *
 * Writes are persisted and published under one mutex. Consumers can therefore
 * treat completion of the returned [IO] as the configuration's linearization
 * point, while the revision preserves transitions even when the final values are
 * equal to values used by an older approval.
 */
class AgentApprovalCacheConfigState<P : Any>(
    private val loadApprovalWindow: suspend () -> Duration,
    private val loadCachePolicy: suspend () -> P,
) : AgentApprovalCacheConfigProvider<P> {
    private val mutex = Mutex()
    private val state = MutableStateFlow<AgentApprovalCacheConfig<P>?>(null)

    override suspend fun get(): AgentApprovalCacheConfig<P> = mutex.withLock {
        getOrLoadLocked()
    }

    fun approvalWindow(): Flow<Duration> = flow {
        get()
        state
            .filterNotNull()
            .map { it.approvalWindow }
            .distinctUntilChanged()
            .collect { emit(it) }
    }

    fun cachePolicy(): Flow<P> = flow {
        get()
        state
            .filterNotNull()
            .map { it.cachePolicy }
            .distinctUntilChanged()
            .collect { emit(it) }
    }

    fun updateApprovalWindow(
        approvalWindow: Duration,
        persist: IO<Unit>,
    ): IO<Unit> = {
        mutex.withLock {
            val current = getOrLoadLocked()
            try {
                persist()
            } catch (e: Throwable) {
                invalidateAfterFailedPersistenceLocked(current)
                throw e
            }
            if (current.approvalWindow != approvalWindow) {
                state.value = current.copy(
                    approvalWindow = approvalWindow,
                    revision = current.revision + 1L,
                )
            }
        }
    }

    fun updateCachePolicy(
        cachePolicy: P,
        persist: IO<Unit>,
    ): IO<Unit> = {
        mutex.withLock {
            val current = getOrLoadLocked()
            try {
                persist()
            } catch (e: Throwable) {
                invalidateAfterFailedPersistenceLocked(current)
                throw e
            }
            if (current.cachePolicy != cachePolicy) {
                state.value = current.copy(
                    cachePolicy = cachePolicy,
                    revision = current.revision + 1L,
                )
            }
        }
    }

    private suspend fun getOrLoadLocked(): AgentApprovalCacheConfig<P> =
        state.value ?: AgentApprovalCacheConfig(
            approvalWindow = loadApprovalWindow(),
            cachePolicy = loadCachePolicy(),
            revision = 0L,
        ).also { state.value = it }

    private fun invalidateAfterFailedPersistenceLocked(
        current: AgentApprovalCacheConfig<P>,
    ) {
        // Some preference backends publish their in-memory value before the
        // durable write completes. Keep the last authoritative values, but
        // invalidate approvals because the backing state may now be uncertain.
        state.value = current.copy(revision = current.revision + 1L)
    }
}

/**
 * Compatibility adapter for isolated constructors and tests that still supply
 * independent flows. Production settings expose [AgentApprovalCacheConfigState]
 * directly, so security-sensitive reads do not depend on this collector.
 */
internal fun <P : Any> flowBackedAgentApprovalCacheConfigProvider(
    approvalWindow: Flow<Duration>,
    cachePolicy: Flow<P>,
    scope: CoroutineScope,
): AgentApprovalCacheConfigProvider<P> = FlowBackedAgentApprovalCacheConfigProvider(
    approvalWindow = approvalWindow,
    cachePolicy = cachePolicy,
    scope = scope,
)

private class FlowBackedAgentApprovalCacheConfigProvider<P : Any>(
    private val approvalWindow: Flow<Duration>,
    private val cachePolicy: Flow<P>,
    scope: CoroutineScope,
) : AgentApprovalCacheConfigProvider<P> {
    private val mutex = Mutex()
    private val state = MutableStateFlow<AgentApprovalCacheConfig<P>?>(null)

    init {
        approvalWindow
            .combine(cachePolicy, ::Pair)
            .onEach { (window, policy) ->
                mutex.withLock {
                    val current = state.value
                    state.value = when {
                        current == null -> AgentApprovalCacheConfig(
                            approvalWindow = window,
                            cachePolicy = policy,
                            revision = 0L,
                        )

                        current.approvalWindow != window || current.cachePolicy != policy ->
                            AgentApprovalCacheConfig(
                                approvalWindow = window,
                                cachePolicy = policy,
                                revision = current.revision + 1L,
                            )

                        else -> current
                    }
                }
            }
            .launchIn(scope)
    }

    override suspend fun get(): AgentApprovalCacheConfig<P> =
        state.value ?: approvalWindow
            .combine(cachePolicy, ::Pair)
            .first()
            .let { (window, policy) ->
                mutex.withLock {
                    state.value ?: AgentApprovalCacheConfig(
                        approvalWindow = window,
                        cachePolicy = policy,
                        revision = 0L,
                    ).also { state.value = it }
                }
            }
}
