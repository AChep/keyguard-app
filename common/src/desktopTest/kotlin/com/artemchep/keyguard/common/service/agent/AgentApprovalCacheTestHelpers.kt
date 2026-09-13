package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Starts [request] while an approval window update is blocked inside
 * its persist step, applies [whileBlocked], then releases the update
 * and returns the request result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T> TestScope.finishAfterBlockedApprovalCacheAccess(
    config: AgentApprovalCacheConfigState<AgentApprovalCachePolicy>,
    request: suspend () -> T,
    whileBlocked: () -> Unit,
): T = coroutineScope {
    val updateStarted = CompletableDeferred<Unit>()
    val finishUpdate = CompletableDeferred<Unit>()
    val update = async {
        config.updateApprovalWindow(Duration.INFINITE, persist = {
            updateStarted.complete(Unit)
            finishUpdate.await()
        })()
    }
    updateStarted.await()
    val pending = async { request() }
    runCurrent()
    assertEquals(false, pending.isCompleted)
    whileBlocked()
    finishUpdate.complete(Unit)
    update.await()
    pending.await()
}

/** Runs a settings change after a request reaches a suspending eligibility read. */
internal suspend fun <T> finishAfterBlockedAgentRead(
    beforeRead: (suspend () -> Unit) -> Unit,
    request: suspend () -> T,
    whileBlocked: suspend () -> Unit,
): T = coroutineScope {
    val started = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    beforeRead {
        started.complete(Unit)
        finish.await()
    }
    val pending = async { request() }
    started.await()
    whileBlocked()
    finish.complete(Unit)
    pending.await()
}

internal enum class ApprovalCacheInvalidation {
    Disable,
    Window,
    Policy,
    WindowRoundTrip,
    PolicyRoundTrip,
    PersistenceFailure,
    ;

    suspend fun apply(config: AgentApprovalCacheConfigState<AgentApprovalCachePolicy>) {
        when (this) {
            Disable -> config.updateApprovalWindow(Duration.ZERO, persist = {})()
            Window -> config.updateApprovalWindow(5.minutes, persist = {})()
            Policy -> config.updateCachePolicy(AgentApprovalCachePolicy.Connection, persist = {})()
            WindowRoundTrip -> {
                config.updateApprovalWindow(Duration.ZERO, persist = {})()
                config.updateApprovalWindow(Duration.INFINITE, persist = {})()
            }
            PolicyRoundTrip -> {
                config.updateCachePolicy(AgentApprovalCachePolicy.Connection, persist = {})()
                config.updateCachePolicy(AgentApprovalCachePolicy.Default, persist = {})()
            }
            PersistenceFailure -> assertFailsWith<IllegalStateException> {
                config.updateApprovalWindow(Duration.ZERO, persist = { error("Persistence failed") })()
            }
        }
    }
}
