package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The authenticated streams of a session manager that are not closed yet. A cancelled
 * connection race may drop one before any session takes it: [closingLeft] closes what is
 * left once the manager stops, so no stream holds its native Noise session past it.
 */
internal class KeyguardOpenStreams {
    private val lock = SynchronizedObject()

    private val open = mutableSetOf<ByteStream>()

    /** Returns [stream], kept here until it is closed. */
    fun track(stream: ByteStream): ByteStream = Tracked(stream)
        .also { synchronized(lock) { open += it } }

    /** Runs [block], then closes every stream that is still open, however it ends. */
    suspend fun <T> closingLeft(block: suspend () -> T): T = try {
        block()
    } finally {
        withContext(NonCancellable) {
            val left = synchronized(lock) { open.toList() }
            // One that fails to close keeps none of the others open.
            left.forEach { runCatchingUnlessCancelled { it.close() } }
        }
    }

    private inner class Tracked(
        private val inner: ByteStream,
    ) : ByteStream by inner {
        override suspend fun close() {
            synchronized(lock) { open -= this }
            inner.close()
        }
    }
}
