package com.artemchep.keyguard.feature.navigation

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Runs tasks one at a time, in the order they were submitted.
 *
 * The thread that submits into an idle relay drains it: it runs its own
 * task and every task submitted meanwhile, re-entrantly or from another
 * thread, until the queue is empty. Any other [submit] only queues the
 * task and returns. No lock is held while a task runs.
 */
internal class NavigationRelay {
    private val lock = SynchronizedObject()

    private val queue = ArrayDeque<() -> Unit>()

    private var draining = false

    /**
     * Queues the [task]. If no one drains the relay, runs it and every
     * queued task on the calling thread before returning.
     *
     * A failing task does not stop the drain: the remaining tasks still run,
     * then the first failure is rethrown to the thread that drained, with
     * the later ones added as suppressed.
     */
    fun submit(task: () -> Unit) {
        val drain = synchronized(lock) {
            queue.addLast(task)
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }
        if (drain) {
            drain()
        }
    }

    // A failing task must not block the queue, so every failure is caught.
    @Suppress("TooGenericExceptionCaught")
    private fun drain() {
        var failure: Throwable? = null
        while (true) {
            val task = synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) {
                    draining = false
                }
                next
            } ?: break
            try {
                task()
            } catch (e: Throwable) {
                val first = failure
                if (first == null) {
                    failure = e
                } else {
                    first.addSuppressed(e)
                }
            }
        }
        failure?.let { throw it }
    }
}
