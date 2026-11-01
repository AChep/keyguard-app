package com.artemchep.keyguard.feature.dragout.compose

import com.artemchep.desktop.NativeDragCallbacks
import com.artemchep.desktop.NativeDragSession
import com.artemchep.keyguard.common.service.dragout.PreparedContent
import com.artemchep.keyguard.common.service.dragout.distinctDragOutFileNames
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragPayload
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import com.artemchep.keyguard.feature.dragout.model.representations
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

/** The files a native drag offers. */
internal sealed interface NativeDragFiles {
    /** Files prepared before the drag starts. */
    class Prepared(val contents: List<PreparedContent>) : NativeDragFiles

    /** The source's [DragOutSource.promisedFileName], prepared once the drag has started. */
    class Promised(val source: DragOutSource) : NativeDragFiles
}

/**
 * Keeps offered files independent of the source screen once a native drag takes
 * ownership. The content store owns the offer deadline: the native session is
 * revoked once every file has retired. A platform that reports [delivered] files
 * retires an accepted offer sooner, once every file is delivered.
 *
 * A promised offer starts with names only. [start] then prepares its files, and
 * readers wait for them.
 */
// Start, promised preparation, delivery and retirement share one lock and one state.
@Suppress("TooManyFunctions")
internal abstract class NativeDragOffer(
    files: NativeDragFiles,
) : AutoCloseable, NativeDragCallbacks {
    private val lock = Any()
    private val promised = (files as? NativeDragFiles.Promised)?.source
    private val settled = CountDownLatch(if (promised != null) 1 else 0)
    private var state = PREPARED
    private var offered = (files as? NativeDragFiles.Prepared)?.contents
    private var session: NativeDragSession? = null
    private var preparation: Job? = null
    private val onEnded = AtomicReference<(() -> Unit)?>(null)

    /** Names unique ignoring case; a drop into one folder needs distinct names. */
    protected val names: List<String> = distinctDragOutFileNames(
        offered?.map { it.name } ?: listOfNotNull(promised?.promisedFileName),
    )

    // Delivered files and the drop outcome, guarded by lock.
    private val deliveries = BooleanArray(names.size)
    private var undelivered = names.size
    private var accepted = false

    /** Files known before the drag starts. */
    protected val contents: List<PreparedContent>
        get() = checkNotNull(synchronized(lock) { offered }) { "The drag offer is promised" }

    init {
        require(names.isNotEmpty()) { "A native drag must offer a file" }
    }

    /** Starts the platform drag; returns its session, or null when native took no ownership. */
    protected abstract fun startNative(): NativeDragSession?

    @Suppress("TooGenericExceptionCaught") // Startup failure must retire the native capability before propagating.
    fun start(onEnded: () -> Unit): Boolean {
        val ready = synchronized(lock) {
            if (state != PREPARED) return false
            state = STARTED
            offered
        }
        this.onEnded.set(onEnded)
        val session = try {
            ready?.forEach(PreparedContent::handoff)
            startNative().also { if (it == null) retired() else install(it) }
        } catch (failure: Throwable) {
            retired()
            throw failure
        }
        // A promised file is prepared only once the session is installed to receive it.
        if (session != null) promised?.let(::follow)
        return session != null
    }

    /** Prepares a promised offer's files from [source] once the drag has started; retirement cancels it. */
    private fun follow(source: DragOutSource) {
        val job = source.start(::fulfill)
        val cancel = synchronized(lock) {
            if (state == STARTED) preparation = job
            state != STARTED
        }
        if (cancel) job.cancel()
    }

    private fun install(session: NativeDragSession) {
        val (ready, delivered) = synchronized(lock) {
            this.session = session
            offered to deliveredSessionLocked()
        }
        ready?.let { revokeOnRetirement(it, session) }
        // A drop can be accepted and written before startup returns.
        delivered?.revoke()
    }

    private fun fulfill(payload: DragPayload?) {
        val files = payload?.representations<DragRepresentation.File>()?.map { it.content }
            ?.takeIf { it.size == names.size }
        val session = synchronized(lock) {
            val pending = state == STARTED && offered == null
            this.session?.takeIf { pending && files != null }?.also {
                offered = files
                preparation = null
            }
        }
        if (files == null || session == null) {
            payload?.close()
            // Wake waiting writers first: native retires only after they return.
            retired()
            synchronized(lock) { this.session }?.revoke()
            return
        }
        files.forEach(PreparedContent::handoff)
        revokeOnRetirement(files, session)
        settled.countDown()
    }

    private fun revokeOnRetirement(contents: List<PreparedContent>, session: NativeDragSession) {
        val remaining = AtomicInteger(contents.size)
        contents.forEach { content ->
            content.invokeOnRetirement {
                if (remaining.decrementAndGet() == 0) session.revoke()
            }
        }
    }

    /** The file at [index], while the offer still accepts readers. Blocks until a promised file is prepared. */
    protected fun offered(index: Int): PreparedContent {
        settled.await()
        val content = synchronized(lock) { offered.takeIf { state == STARTED } }?.get(index)
        check(content != null && content.isAvailable()) { "The drag offer has expired" }
        return content
    }

    /**
     * Records that file [index], by position in [names], is complete at the drop's
     * destination. Once the drop is accepted and every file is delivered, the native
     * session is revoked: in-flight reads finish, then [retired] closes the files.
     * Repeated and unknown indices are ignored. Any thread.
     */
    protected fun delivered(index: Int) {
        val session = synchronized(lock) {
            if (index !in deliveries.indices || deliveries[index]) return
            deliveries[index] = true
            undelivered--
            deliveredSessionLocked()
        }
        session?.revoke()
    }

    /** The session to revoke when an accepted drop has every file delivered. */
    private fun deliveredSessionLocked(): NativeDragSession? =
        session.takeIf { accepted && undelivered == 0 && state == STARTED }

    override fun ended(accepted: Boolean) {
        // This callback can capture the node, window and screen. Never retain it
        // while an accepted offer stays readable.
        onEnded.getAndSet(null)?.invoke()
        if (!accepted) {
            retired()
            return
        }
        val delivered = synchronized(lock) {
            this.accepted = true
            deliveredSessionLocked()
        }
        delivered?.revoke()
    }

    override fun retired() {
        val retiring = synchronized(lock) {
            if (state == CLOSED) null else {
                state = CLOSED
                offered to preparation.also { preparation = null }
            }
        }
        settled.countDown()
        retiring?.second?.cancel()
        retiring?.first?.forEach(PreparedContent::close)
        onEnded.getAndSet(null)?.invoke()
    }

    override fun close() {
        // Once started, the native outcome and the store's deadline own retirement.
        // Disposing the source node must not invalidate an accepted drop.
        val owned = synchronized(lock) {
            if (state != PREPARED) return
            state = CLOSED
            offered
        }
        settled.countDown()
        owned?.forEach(PreparedContent::close)
    }

    private companion object {
        const val PREPARED = 0
        const val STARTED = 1
        const val CLOSED = 2
    }
}
