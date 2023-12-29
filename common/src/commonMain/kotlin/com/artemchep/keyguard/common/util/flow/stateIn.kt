package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val None = Any()

fun <T> Flow<T>.persistingStateIn(
    scope: CoroutineScope,
    started: SharingStarted,
    initialValue: T,
): StateFlow<T> {
    val state = MutableStateFlow(initialValue)
    val job = scope.launchSharing(EmptyCoroutineContext, this, state, started)
    return ReadonlyStateFlow(state, job)
}

suspend fun <T> Flow<T>.persistingStateIn(
    scope: CoroutineScope,
    started: SharingStarted,
): StateFlow<T> {
    val state = MutableStateFlow<Any?>(None)
    val job = scope.launchSharing(EmptyCoroutineContext, this, state, started)

    // Wait till the flow gets
    // its first value.
    state.first { it !== None }

    // We are safe to cast it back.
    return ReadonlyStateFlow(state as StateFlow<T>, job)
}

// Launches sharing coroutine
private fun <T> CoroutineScope.launchSharing(
    context: CoroutineContext,
    upstream: Flow<T>,
    shared: MutableStateFlow<T>,
    started: SharingStarted,
): Job {
    /*
     * Conditional start: in the case when sharing and subscribing happens in the same dispatcher, we want to
     * have the following invariants preserved:
     * * Delayed sharing strategies have a chance to immediately observe consecutive subscriptions.
     *   E.g. in the cases like `flow.shareIn(...); flow.take(1)` we want sharing strategy to see the initial subscription
     * * Eager sharing does not start immediately, so the subscribers have actual chance to subscribe _prior_ to sharing.
     */
    val start =
        if (started == SharingStarted.Eagerly) CoroutineStart.DEFAULT else CoroutineStart.UNDISPATCHED
    return launch(context, start = start) { // the single coroutine to rule the sharing
        // Optimize common built-in started strategies
        when {
            started === SharingStarted.Eagerly -> {
                // collect immediately & forever
                upstream.collect(shared)
            }

            started === SharingStarted.Lazily -> {
                // start collecting on the first subscriber - wait for it first
                shared.subscriptionCount.first { it > 0 }
                upstream.collect(shared)
            }

            else -> {
                // other & custom strategies
                started.command(shared.subscriptionCount)
                    .distinctUntilChanged() // only changes in command have effect
                    .collectLatest { // cancels block on new emission
                        when (it) {
                            SharingCommand.START -> upstream.collect(shared)
                            SharingCommand.STOP -> { /* just cancel and do nothing else */
                            }

                            SharingCommand.STOP_AND_RESET_REPLAY_CACHE -> { /* just cancel and do nothing else */
                            }
                        }
                    }
            }
        }
    }
}

private class ReadonlyStateFlow<T>(
    flow: StateFlow<T>,
    @Suppress("unused")
    private val job: Job?, // keeps a strong reference to the job (if present)
) : StateFlow<T> by flow
