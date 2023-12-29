package com.artemchep.keyguard.common.io

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

fun <T> Flow<T>.throttle(timeoutMillis: Long, sendLastEvent: Boolean = true): Flow<T> =
    if (sendLastEvent) {
        channelFlow {
            val throttler = FlowThrottle(this, timeoutMillis)
            collect {
                throttler.send(it)
            }
        }
    } else {
        flow {
            var lastTime = 0L
            collect { value ->
                val now = System.currentTimeMillis()
                if (lastTime + timeoutMillis > now) {
                    return@collect
                }

                lastTime = now
                emit(value)
            }
        }
    }

private class FlowThrottle<T>(
    private val producer: ProducerScope<T>,
    private val timeoutMillis: Long,
) {
    companion object {
        private val NOTHING = Any()
    }

    private var tickerJob: Job? = null

    /**
     * The value that is waiting to be sent, [NOTHING] means that
     * no value is waiting to be sent
     */
    private var unsentValue: Any? = NOTHING

    private var lastTime = 0L

    suspend fun send(value: T) {
        val now = System.currentTimeMillis()
        val dt = lastTime + timeoutMillis - now
        if (dt > 0L) {
            // We can not send this value, so remember it and maybe send
            // it next time.
            unsentValue = value
            // Schedule a send job.
            if (tickerJob?.isActive != true) {
                tickerJob = producer.launch {
                    delay(dt)

                    val nextValue = unsentValue
                    if (nextValue !== NOTHING) {
                        send(nextValue as T)
                    }
                }
            }
            return
        }

        lastTime = System.currentTimeMillis()
        unsentValue = NOTHING
        tickerJob?.cancel()

        producer.trySend(value)
    }
}
