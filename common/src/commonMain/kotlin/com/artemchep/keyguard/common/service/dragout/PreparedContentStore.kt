package com.artemchep.keyguard.common.service.dragout

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.download.DOWNLOAD_PLAINTEXT_MAXIMUM_BYTES
import com.artemchep.keyguard.common.service.download.DOWNLOAD_PLAINTEXT_MEMORY_LIMIT_BYTES
import com.artemchep.keyguard.util.io.spool.ByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteSnapshotReader
import com.artemchep.keyguard.util.io.spool.RandomAccessByteSnapshot
import com.artemchep.keyguard.util.io.spool.requireSnapshotReadRange
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.IOException

/**
 * Application-owned prepared bytes; the store never creates a public plaintext file.
 * A failed backing close keeps its quota charged so repeated failures cannot grow storage unboundedly.
 */
// Reservation, reader and expiry transitions share one lock and one ownership protocol.
@Suppress("TooManyFunctions")
class PreparedContentStore internal constructor(
    private val scope: CoroutineScope,
    private val logRepository: LogRepository,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val limits: PreparedContentLimits = PreparedContentLimits(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AutoCloseable {
    private val lock = SynchronizedObject()
    private var reservation: Reservation? = null
    private val entries = mutableSetOf<Entry>()
    private var retainedBytes = 0L
    private var readers = 0
    private var closed = false

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    /**
     * Reserve the entire allowed write before any staging allocation occurs. Published
     * content retires once [owner] completes; readers already open may finish.
     */
    internal fun reserve(maximumBytes: Long? = null, owner: Job? = null): Reservation = synchronized(lock) {
        require(maximumBytes == null || maximumBytes > 0) { "Content byte limit must be positive" }
        check(!closed) { "Content store is closed" }
        check(reservation == null) { "A content preparation is already active" }
        val available = limits.maximumBytes - retainedBytes
        check(available > 0) { "Prepared content byte budget is exhausted" }
        val allowance = minOf(available, maximumBytes ?: available)
        Reservation(
            SpoolLimits(
                memoryBytes = minOf(limits.memoryBytes, allowance),
                maximumBytes = allowance,
            ),
            owner,
        ).also {
            reservation = it
            retainedBytes += allowance
        }
    }

    internal inner class Reservation internal constructor(
        val limits: SpoolLimits,
        private val owner: Job?,
    ) : AutoCloseable {
        /** On failure the caller still owns [snapshot] and must close it. */
        fun publish(name: String, snapshot: ByteSnapshot): PreparedContent {
            // Seekable recipients must never fall back to replaying the content from byte zero.
            require(snapshot is RandomAccessByteSnapshot) { "Prepared content must be seekable" }
            val size = snapshot.size
            val safeName = dragOutFileName(name)
            val entry = synchronized(lock) {
                check(!closed && reservation === this) { "Content preparation is no longer active" }
                require(size in 0..limits.maximumBytes) { "Content exceeds its reserved byte budget" }
                Entry(snapshot, size, deadline()).also {
                    reservation = null
                    retainedBytes -= limits.maximumBytes - size
                    entries += it
                }
            }
            scheduleExpiry(entry, entry.generation)
            owner?.let { retireWith(entry, it) }
            return PreparedContent(
                name = safeName,
                size = size,
                onOpenReader = { openReader(entry) },
                isOffered = { isAvailable(entry) },
                onHandoff = { handoff(entry) },
                onClose = { retire(entry) },
                onRetirement = { action -> invokeOnRetirement(entry, action) },
            )
        }

        override fun close() {
            synchronized(lock) {
                if (reservation === this) {
                    reservation = null
                    retainedBytes -= limits.maximumBytes
                }
            }
        }
    }

    private class Entry(
        var snapshot: RandomAccessByteSnapshot?,
        val size: Long,
        var deadline: TimeMark,
    ) {
        var accepting = true
        var handedOff = false
        var readers = 0
        var generation = 0L
        var expiry: Job? = null
        var cleaning = false
        val retirementActions = mutableListOf<() -> Unit>()
    }

    private class Retirement(val timer: Job?, val cleanup: Entry?, val actions: List<() -> Unit>)

    private fun deadline(): TimeMark = timeSource.markNow() + limits.offerLifetimeMillis.milliseconds

    /**
     * Runs [block] under the lock with whether [entry] still accepts work, first
     * retiring it if its deadline has passed. The retirement finishes after unlocking.
     */
    private inline fun <T> withLiveEntry(entry: Entry, block: (accepting: Boolean) -> T): T {
        var retirement: Retirement? = null
        val result = synchronized(lock) {
            if (entry.accepting && entry.deadline.hasPassedNow()) retirement = retireLocked(entry)
            block(entry.accepting)
        }
        retirement?.let(::finishRetirement)
        return result
    }

    private fun isAvailable(entry: Entry): Boolean = withLiveEntry(entry) { it }

    private fun openReader(entry: Entry): PreparedContentReader {
        val accepted = withLiveEntry(entry) { accepting ->
            if (accepting) {
                check(readers < limits.maximumReaders) { "Too many content readers" }
                readers += 1
                entry.readers += 1
            }
            accepting
        }
        check(accepted) { "Content offer has expired" }
        return EntryReader(entry)
    }

    private inner class EntryReader(private val entry: Entry) : PreparedContentReader {
        private val readerLock = SynchronizedObject()
        private var reader: ByteSnapshotReader? = null
        private var readerClosed = false

        @Suppress("TooGenericExceptionCaught")
        override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
            requireSnapshotReadRange(position, data, startIndex, endIndex)
            var failure: Throwable? = null
            var cleanupFailed = false
            val count = synchronized(readerLock) {
                check(!readerClosed) { "Content reader is closed" }
                if (startIndex == endIndex || position >= entry.size) return@synchronized 0
                try {
                    // Opened lazily, so admission never performs backing I/O on the caller's thread.
                    val backing = reader ?: checkNotNull(entry.snapshot).openReader().also { reader = it }
                    val requested = minOf((endIndex - startIndex).toLong(), entry.size - position).toInt()
                    val read = backing.readAt(position, data, startIndex, startIndex + requested)
                    if (read != requested) throw IOException("Prepared content ended early")
                    requested
                } catch (error: Throwable) {
                    data.fill(0, startIndex, endIndex)
                    failure = error
                    readerClosed = true
                    val closeFailure = closeBacking()
                    if (closeFailure != null) {
                        cleanupFailed = true
                        failure = combineReaderFailures(error, closeFailure)
                    }
                    0
                }
            }
            if (failure != null) releaseReader(entry)
            if (cleanupFailed) reportCleanupFailure()
            failure?.let { throw it }
            return count
        }

        @Suppress("TooGenericExceptionCaught")
        private fun closeBacking(): Throwable? {
            val owned = reader
            reader = null
            return try {
                owned?.close()
                null
            } catch (error: Throwable) {
                error
            }
        }

        override fun close() {
            val failure = synchronized(readerLock) {
                if (readerClosed) return
                readerClosed = true
                closeBacking()
            }
            releaseReader(entry)
            if (failure != null) {
                reportCleanupFailure()
                throw failure
            }
        }
    }

    private fun releaseReader(entry: Entry) {
        val cleanup = synchronized(lock) {
            readers -= 1
            entry.readers -= 1
            cleanupIfReadyLocked(entry)
        }
        cleanup?.let(::cleanup)
    }

    private fun handoff(entry: Entry) {
        var retirement: Retirement? = null
        val generation = synchronized(lock) {
            when {
                !entry.accepting || entry.handedOff -> null
                entry.deadline.hasPassedNow() -> {
                    retirement = retireLocked(entry)
                    null
                }
                else -> {
                    entry.handedOff = true
                    entry.deadline = deadline()
                    entry.generation += 1
                    entry.generation
                }
            }
        }
        retirement?.let(::finishRetirement)
        generation?.let { scheduleExpiry(entry, it) }
    }

    private fun scheduleExpiry(entry: Entry, generation: Long) {
        val task = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val remaining = synchronized(lock) {
                    if (!entry.accepting || entry.generation != generation) return@launch
                    -entry.deadline.elapsedNow()
                }
                if (remaining.isPositive()) delay(remaining) else break
            }
            retire(entry, generation)
        }
        var previous: Job? = null
        val installed = synchronized(lock) {
            if (!entry.accepting || entry.generation != generation) false else {
                previous = entry.expiry
                entry.expiry = task
                true
            }
        }
        previous?.cancel()
        if (installed) task.start() else task.cancel()
    }

    private fun retire(entry: Entry, generation: Long? = null) {
        val retirement = synchronized(lock) {
            if (generation != null && entry.generation != generation) null else retireLocked(entry)
        }
        retirement?.let(::finishRetirement)
    }

    private fun retireLocked(entry: Entry): Retirement {
        entry.accepting = false
        val timer = entry.expiry
        entry.expiry = null
        val actions = entry.retirementActions.toList()
        entry.retirementActions.clear()
        return Retirement(timer, cleanupIfReadyLocked(entry), actions)
    }

    /** Runs inline if [owner] has already completed. */
    private fun retireWith(entry: Entry, owner: Job) {
        val handle = owner.invokeOnCompletion { retire(entry) }
        invokeOnRetirement(entry, handle::dispose)
    }

    private fun invokeOnRetirement(entry: Entry, action: () -> Unit): AutoCloseable {
        val registered = withLiveEntry(entry) { accepting ->
            if (accepting) entry.retirementActions += action
            accepting
        }
        if (!registered) {
            runRetirementAction(action)
            return AutoCloseable {}
        }
        return AutoCloseable {
            synchronized(lock) { entry.retirementActions.remove(action) }
        }
    }

    // Transports release platform registrations here; one failure must not skip the others.
    @Suppress("TooGenericExceptionCaught")
    private fun runRetirementAction(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            failure.throwIfFatalOrCancellation()
            reportCleanupFailure()
        }
    }

    private fun cleanupIfReadyLocked(entry: Entry): Entry? = if (
        !entry.accepting && entry.readers == 0 && !entry.cleaning
    ) {
        entry.cleaning = true
        entry
    } else null

    private fun finishRetirement(retirement: Retirement) {
        retirement.timer?.cancel()
        retirement.actions.forEach(::runRetirementAction)
        retirement.cleanup?.let(::cleanup)
    }

    // Closing a window/vault scope must not cancel a storage release already owed to a reader.
    @Suppress("TooGenericExceptionCaught")
    private fun cleanup(entry: Entry) {
        scope.launch(NonCancellable + cleanupDispatcher) {
            var released = false
            try {
                checkNotNull(entry.snapshot).close()
                released = true
            } catch (failure: Throwable) {
                reportCleanupFailure()
                failure.throwIfFatalOrCancellation()
            } finally {
                if (released) {
                    synchronized(lock) {
                        entry.snapshot = null
                        if (entries.remove(entry)) retainedBytes -= entry.size
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun reportCleanupFailure() {
        try {
            logRepository.post(
                tag = "PreparedContent",
                message = "Failed to close prepared content storage",
                level = LogLevel.ERROR,
            )
        } catch (failure: Throwable) {
            failure.throwIfFatalOrCancellation()
        }
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            reservation = null
            retainedBytes = entries.sumOf { it.size }
            entries.map(::retireLocked)
        }
        retired.forEach(::finishRetirement)
    }
}


private fun combineReaderFailures(readFailure: Throwable, closeFailure: Throwable): Throwable {
    if (readFailure === closeFailure) return readFailure
    val primary = when {
        readFailure is Error -> readFailure
        closeFailure is Error -> closeFailure
        readFailure is CancellationException -> readFailure
        closeFailure is CancellationException -> closeFailure
        else -> readFailure
    }
    primary.addSuppressed(if (primary === readFailure) closeFailure else readFailure)
    return primary
}

internal data class PreparedContentLimits(
    val maximumReaders: Int = 8,
    val maximumBytes: Long = DOWNLOAD_PLAINTEXT_MAXIMUM_BYTES,
    val memoryBytes: Long = DOWNLOAD_PLAINTEXT_MEMORY_LIMIT_BYTES,
    val offerLifetimeMillis: Long = 5.minutes.inWholeMilliseconds,
) {
    init {
        require(maximumReaders > 0)
        require(maximumBytes > 0 && memoryBytes in 0..maximumBytes)
        require(offerLifetimeMillis > 0)
    }
}
