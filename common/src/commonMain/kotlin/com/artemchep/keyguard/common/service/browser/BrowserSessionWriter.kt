package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserFragments
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writes the messages of one session, each split into fragments that are
 * never interleaved with another message's.
 */
internal class BrowserSessionWriter(
    private val stream: ByteStream,
) {
    private val mutex = Mutex()

    /** Whether the session wrote its last message, see [close]. Guarded by the mutex. */
    private var closed = false

    /**
     * Writes the message that [build] makes, or nothing for `null`. [build]
     * runs in the order of the writes: what it reads is what the extension
     * learns at that point of the session. Once [closed], writes nothing and
     * doesn't run [build].
     */
    suspend fun send(build: () -> ByteArray?) = mutex.withLock {
        if (closed) return@withLock
        build()?.let { write(it) }
    }

    /**
     * Writes the last message of the session that [build] makes, if any:
     * every later [send] writes nothing.
     */
    suspend fun close(build: () -> ByteArray?) = mutex.withLock {
        if (closed) return@withLock
        closed = true
        build()?.let { write(it) }
    }

    private suspend fun write(message: ByteArray) = message.useAndClear {
        BrowserFragments.split(it).forEach { fragment ->
            fragment.useAndClear { bytes -> stream.write(bytes) }
        }
    }
}
