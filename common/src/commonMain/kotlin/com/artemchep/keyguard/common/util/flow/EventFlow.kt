package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * @author Artem Chepurnyi
 */
open class EventFlow<T>(
    extraBufferCapacity: Int = DEFAULT_EXTRA_BUFFER_CAPACITY,
    onBufferOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST,
) : Flow<T> {
    private val events = MutableSharedFlow<T>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = onBufferOverflow,
    )

    /**
     * Returns `true` if the live flow has any active observers,
     * `false` otherwise.
     */
    val isActive: Boolean
        get() = collectorCount.value > 0

    private val collectorCount = MutableStateFlow(0)

    fun emit(value: T) {
        events.tryEmit(value)
    }

    suspend fun emitSuspending(value: T) {
        events.emit(value)
    }

    open fun onActive() {
        // By default does nothing.
    }

    open fun onInactive() {
        // By default does nothing.
    }

    fun asFlow(): Flow<T> = this

    override suspend fun collect(collector: FlowCollector<T>) {
        val wasInactive = incrementCollectorCount()
        try {
            if (wasInactive) {
                onActive()
            }

            events.collect(collector)
        } finally {
            val isInactive = decrementCollectorCount()
            if (isInactive) {
                onInactive()
            }
        }
    }

    private fun incrementCollectorCount(): Boolean {
        while (true) {
            val current = collectorCount.value
            val updated = current + 1
            if (collectorCount.compareAndSet(current, updated)) {
                return current == 0
            }
        }
    }

    private fun decrementCollectorCount(): Boolean {
        while (true) {
            val current = collectorCount.value
            require(current > 0)

            val updated = current - 1
            if (collectorCount.compareAndSet(current, updated)) {
                return updated == 0
            }
        }
    }
}

private const val DEFAULT_EXTRA_BUFFER_CAPACITY = 64
