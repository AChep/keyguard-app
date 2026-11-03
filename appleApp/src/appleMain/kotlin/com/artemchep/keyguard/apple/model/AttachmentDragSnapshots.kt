@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.dragout.PreparedContent
import com.artemchep.keyguard.common.service.dragout.PreparedContentReader
import com.artemchep.keyguard.feature.dragout.model.DragOutCapabilities
import com.artemchep.keyguard.feature.dragout.model.DragOutSource
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import com.artemchep.keyguard.feature.dragout.model.representations
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.util.io.toNSData
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import platform.Foundation.NSData
import platform.posix.EINTR
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_WRONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.errno
import platform.posix.fstat
import platform.posix.lstat
import platform.posix.open
import platform.posix.stat
import platform.posix.unlink
import platform.posix.close as closeDescriptor
import platform.posix.write as writeDescriptor

/** The largest chunk one read copies. */
private const val CHUNK_BYTES = 64 * 1024

/** The bounded iOS data representation; preparation enforces the same cap. */
private val DATA_MAXIMUM_BYTES: Long =
    checkNotNull(DragOutCapabilities.of(Platform.Mobile.Ios.Native).fileMaximumBytes)

/** A capability bound to the producing detail presentation, never a global action lookup. */
class AttachmentDragSnapshot internal constructor(private val drag: DragOutSource) {
    val name: String get() = drag.label
    val promisedFileName: String? get() = drag.promisedFileName

    fun prepare(onResult: (AttachmentDragContentSnapshot?) -> Unit): KeyguardCancellable =
        KeyguardCancellable(drag.start { payload ->
            // The native attachment row offers exactly one file.
            val content = payload?.representations<DragRepresentation.File>()?.singleOrNull()?.content
            if (content == null) payload?.close()
            onResult(content?.let(::AttachmentDragContentSnapshot))
        })
}

/** A platform-neutral offer; neither native Apple transport needs a producer file URL. */
class AttachmentDragContentSnapshot internal constructor(private val content: PreparedContent) {
    val name: String get() = content.name
    val size: Long get() = content.size
    fun isAvailable(): Boolean = content.isAvailable()
    fun handoff() = content.handoff()
    fun close() = content.close()

    /**
     * Runs [action] once the offer stops accepting readers: on expiry, [close], or the end
     * of the vault session. Runs it at once if the offer already has. It may run on any thread.
     */
    fun invokeOnRetirement(action: () -> Unit): KeyguardCancellable {
        val registration = content.invokeOnRetirement(action)
        return KeyguardCancellable(onCancel = registration::close)
    }

    /** Complete immutable data for the bounded iOS representation. Invoke only on a worker. */
    @Suppress("TooGenericExceptionCaught")
    fun loadData(cancellation: AttachmentDragReadCancellationSnapshot): AttachmentDragReadResult = try {
        require(size in 0..DATA_MAXIMUM_BYTES) {
            "Attachment exceeds the data representation limit"
        }
        cancellation.check()
        content.openReader().use { reader ->
            val bytes = ByteArray(size.toInt())
            try {
                var offset = 0
                while (offset < bytes.size) {
                    cancellation.check()
                    val end = minOf(bytes.size, offset + CHUNK_BYTES)
                    check(reader.readAt(offset.toLong(), bytes, offset, end) == end - offset)
                    offset = end
                }
                cancellation.check()
                val data = bytes.toNSData()
                cancellation.check()
                AttachmentDragReadResult(data = data)
            } finally {
                bytes.fill(0)
            }
        }
    } catch (failure: Throwable) {
        if (failure.isCancellation()) {
            AttachmentDragReadResult(cancelled = true)
        } else {
            AttachmentDragReadResult()
        }
    }

    /**
     * Writes the whole content into a new file at the absolute [path] a recipient chose,
     * the only plaintext file a drag creates. Fails unless it wrote exactly [size] bytes;
     * a failed write removes the file it created. Blocking: invoke only on a worker.
     */
    @Suppress("TooGenericExceptionCaught")
    fun writeTo(
        path: String,
        cancellation: AttachmentDragReadCancellationSnapshot,
    ): AttachmentDragWriteResult = try {
        cancellation.check()
        var destination: PromiseDestination? = null
        try {
            // Admission first: an expired offer never creates the file.
            content.openReader().use { reader ->
                val created = PromiseDestination.create(path).also { destination = it }
                copy(reader, created, cancellation)
            }
            // Only after the reader released its lease cleanly, as on desktop.
            checkNotNull(destination).commit(size)
        } finally {
            destination?.close()
        }
        AttachmentDragWriteResult.WRITTEN
    } catch (failure: Throwable) {
        if (failure.isCancellation()) {
            AttachmentDragWriteResult.CANCELLED
        } else {
            AttachmentDragWriteResult.FAILED
        }
    }

    private fun copy(
        reader: PreparedContentReader,
        destination: PromiseDestination,
        cancellation: AttachmentDragReadCancellationSnapshot,
    ) {
        val buffer = ByteArray(minOf(size, CHUNK_BYTES.toLong()).toInt())
        try {
            var position = 0L
            while (position < size) {
                cancellation.check()
                val count = minOf(buffer.size.toLong(), size - position).toInt()
                check(reader.readAt(position, buffer, 0, count) == count)
                destination.write(buffer, count)
                position += count
            }
        } finally {
            buffer.fill(0)
        }
    }
}

/** Nonblocking cancellation shared with Progress; it never closes a worker-owned reader inline. */
class AttachmentDragReadCancellationSnapshot {
    private val value = atomic(false)
    val cancelled: Boolean get() = value.value
    fun cancel() { value.value = true }
    internal fun check() {
        if (cancelled) throw CancellationException("Attachment read cancelled")
    }
}

/** Redacted terminal result. No ordinary Kotlin exception crosses into Swift. */
class AttachmentDragReadResult internal constructor(
    val data: NSData? = null,
    val cancelled: Boolean = false,
) {
    val failed: Boolean get() = data == null && !cancelled
}

/** Redacted terminal result. No ordinary Kotlin exception crosses into Swift. */
enum class AttachmentDragWriteResult {
    WRITTEN,
    CANCELLED,
    FAILED,
}

/**
 * A new file at the destination a recipient chose, created exclusively, owner-only and
 * without following a link, like desktopLibNative's file promises. Closing it uncommitted
 * removes it, but only while the path still names the inode created here.
 */
internal class PromiseDestination private constructor(
    private val path: String,
    private var descriptor: Int,
    private val device: Int,
    private val inode: ULong,
) : AutoCloseable {
    private var written = 0L
    private var committed = false

    fun write(data: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val result = data.usePinned {
                writeDescriptor(descriptor, it.addressOf(offset), (count - offset).convert())
            }
            when {
                result > 0 -> offset += result.toInt()
                result < 0 && errno == EINTR -> continue
                else -> throw IOException("Could not write the promised file")
            }
        }
        written += count
    }

    /** Closes the complete file. A short file or a failed close stays uncommitted. */
    fun commit(size: Long) {
        check(written == size) { "The promised file is incomplete" }
        val owned = descriptor
        descriptor = -1
        if (closeDescriptor(owned) != 0) throw IOException("Could not close the promised file")
        committed = true
    }

    override fun close() {
        // The descriptor stays open through the identity check, as on desktop.
        if (!committed) removeIfOwned()
        if (descriptor >= 0) closeDescriptor(descriptor)
        descriptor = -1
    }

    private fun removeIfOwned() = memScoped {
        val current = alloc<stat>()
        if (lstat(path, current.ptr) != 0) return@memScoped
        val regular = current.st_mode.toInt() and S_IFMT == S_IFREG
        // POSIX has no inode-conditional unlink. Never remove an observed replacement.
        if (regular && current.st_dev == device && current.st_ino == inode) unlink(path)
    }

    companion object {
        fun create(path: String): PromiseDestination {
            require(path.startsWith("/")) { "A promised file needs an absolute path" }
            val descriptor = open(
                path,
                O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC,
                S_IRUSR or S_IWUSR,
            )
            if (descriptor < 0) throw IOException("Could not create the promised file")
            val identity = memScoped {
                val info = alloc<stat>()
                if (fstat(descriptor, info.ptr) == 0) info.st_dev to info.st_ino else null
            }
            if (identity == null) {
                closeDescriptor(descriptor)
                throw IOException("Could not inspect the promised file")
            }
            return PromiseDestination(path, descriptor, identity.first, identity.second)
        }
    }
}

/** Rethrows fatal errors. Kotlin use() may suppress a cleanup failure beneath the operation error. */
private fun Throwable.isCancellation(): Boolean {
    suppressedExceptions.firstOrNull { it is Error }?.let { throw it }
    if (this is CancellationException) return true
    throwIfFatalOrCancellation()
    return false
}
