package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.LinkedList
import java.util.Queue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * @author Artem Chepurnyi
 */
open class EventFlow<T>(
    private val context: CoroutineContext = EmptyCoroutineContext,
) : Flow<T> {
    /**
     * Returns `true` if the live flow has any active observers,
     * `false` otherwise.
     */
    val isActive: Boolean
        get() = channels.isNotEmpty()

    val monitor get() = this

    private val channels: Queue<SendChannel<T>> = LinkedList()

    fun emit(value: T) {
        // Copy the list of events to try
        // to send the event to.
        val channels = synchronized(monitor) {
            channels.toList()
        }

        GlobalScope.launch(context) {
            channels.forEach { channel ->
                channel.trySend(value)
            }
        }
    }

    open fun onActive() {
        // By default does nothing.
    }

    open fun onInactive() {
        // By default does nothing.
    }

    fun asFlow(): Flow<T> = flowWithLifecycle<T>(
        onActive = { channel ->
            // Add a channel to the list of
            // consumers.
            synchronized(monitor) {
                channels += channel
                if (channels.size == 1) onActive()
            }
        },
        onInactive = { channel ->
            // Remove a channel from the list of
            // consumers.
            synchronized(monitor) {
                channels -= channel
                if (channels.size == 0) onInactive()
            }
        },
    )

    override suspend fun collect(collector: FlowCollector<T>) = asFlow().collect(collector)
}

private fun <T> flowWithLifecycle(
    onActive: (SendChannel<T>) -> Unit,
    onInactive: (SendChannel<T>) -> Unit,
): Flow<T> = channelFlow {
    try {
        onActive(this)

        // Suspend the flow until manually
        // canceled.
        suspendCancellableCoroutine<Unit> { cont ->
            invokeOnClose {
                cont.resume(Unit)
            }
        }
    } finally {
        onInactive(this)
    }
}
