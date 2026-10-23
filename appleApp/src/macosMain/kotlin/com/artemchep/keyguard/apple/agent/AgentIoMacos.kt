package com.artemchep.keyguard.apple.agent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.darwin.DISPATCH_SOURCE_TYPE_READ
import platform.darwin.DISPATCH_SOURCE_TYPE_WRITE
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_resume
import platform.darwin.dispatch_source_cancel
import platform.darwin.dispatch_source_create
import platform.darwin.dispatch_source_set_cancel_handler
import platform.darwin.dispatch_source_set_event_handler
import platform.darwin.dispatch_source_type_t
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.QOS_CLASS_UTILITY
import platform.posix.errno
import platform.posix.read
import platform.posix.write

/** Suspends until [fd] has data, EOF, or an error, without holding a thread. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun awaitReadable(fd: Int) = awaitDescriptor(fd, DISPATCH_SOURCE_TYPE_READ)

/** Suspends until [fd] has buffer space, a closed peer, or an error. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun awaitWritable(fd: Int) = awaitDescriptor(fd, DISPATCH_SOURCE_TYPE_WRITE)

@OptIn(ExperimentalForeignApi::class)
private suspend fun awaitDescriptor(fd: Int, type: dispatch_source_type_t?) {
    val ready = CompletableDeferred<Unit>()
    val released = CompletableDeferred<Unit>()
    val queue = dispatch_get_global_queue(QOS_CLASS_UTILITY.convert(), 0u)
    val source = checkNotNull(dispatch_source_create(type, fd.convert(), 0u, queue)) {
        "Could not watch agent descriptor"
    }
    dispatch_source_set_event_handler(source) { ready.complete(Unit) }
    dispatch_source_set_cancel_handler(source) { released.complete(Unit) }
    dispatch_resume(source)
    try {
        ready.await()
    } finally {
        dispatch_source_cancel(source)
        // GCD may use the descriptor until the cancel handler runs. Callers close
        // it only after this returns, so a reused descriptor is never watched.
        withContext(NonCancellable) { released.await() }
    }
}

@Suppress("ReturnCount") // Native IO distinguishes EOF, failure, and a complete bounded packet.
@OptIn(ExperimentalForeignApi::class)
internal suspend fun readFully(fd: Int, size: Int): ByteArray? {
    val bytes = ByteArray(size)
    var complete = false
    try {
        var offset = 0
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            val count = bytes.usePinned { read(fd, it.addressOf(offset), (size - offset).convert()) }
            when {
                count > 0 -> offset += count.toInt()
                count == 0L -> return null
                errno == EINTR -> continue
                errno == EAGAIN || errno == EWOULDBLOCK -> awaitReadable(fd)
                else -> return null
            }
        }
        complete = true
        return bytes
    } finally {
        if (!complete) bytes.fill(0)
    }
}

@Suppress("ReturnCount") // Native IO distinguishes EOF, failure, and a completed write.
@OptIn(ExperimentalForeignApi::class)
internal suspend fun writeFully(fd: Int, bytes: ByteArray): Boolean {
    var offset = 0
    while (offset < bytes.size) {
        currentCoroutineContext().ensureActive()
        val count = bytes.usePinned { write(fd, it.addressOf(offset), (bytes.size - offset).convert()) }
        when {
            count > 0 -> offset += count.toInt()
            count == 0L -> return false
            errno == EINTR -> continue
            errno == EAGAIN || errno == EWOULDBLOCK -> awaitWritable(fd)
            else -> return false
        }
    }
    return true
}

/** Discards everything [fd] produces until EOF, without retaining it. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun drain(fd: Int) {
    val buffer = ByteArray(4096)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
        when {
            count > 0 -> buffer.fill(0)
            count == 0L -> return
            errno == EINTR -> continue
            errno == EAGAIN || errno == EWOULDBLOCK -> awaitReadable(fd)
            else -> return
        }
    }
}
