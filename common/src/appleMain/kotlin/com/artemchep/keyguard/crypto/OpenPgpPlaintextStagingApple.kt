package com.artemchep.keyguard.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import platform.Foundation.NSTemporaryDirectory
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.mkstemp
import platform.posix.pread as posixPread
import platform.posix.unlink
import platform.posix.write as posixWrite

internal actual fun createPrivateTemporaryStorage(): PrivateTemporaryStorage =
    createPrivateTemporaryStorageApple()

internal fun closeAppleStagingResources(
    closeSink: () -> Unit,
    closeDescriptor: () -> Boolean,
) {
    val sinkFailure = runCatching(closeSink).exceptionOrNull()
    val descriptorFailure: Throwable? = runCatching(closeDescriptor).fold(
        onSuccess = { closed -> if (closed) null else IOException("Could not close staging file") },
        onFailure = { failure -> failure },
    )
    if (sinkFailure != null) {
        descriptorFailure?.let(sinkFailure::addSuppressed)
        throw sinkFailure
    }
    descriptorFailure?.let { throw it }
}

@OptIn(ExperimentalForeignApi::class)
private fun createPrivateTemporaryStorageApple(): PrivateTemporaryStorage = memScoped {
    val pathTemplate = "${NSTemporaryDirectory().trimEnd('/')}" +
        "/keyguard-private-XXXXXX"
    val mutablePathTemplate = pathTemplate.cstr.getPointer(this)
    val descriptor = mkstemp(mutablePathTemplate)
    check(descriptor >= 0) {
        "Could not create staging file"
    }
    val pathString = mutablePathTemplate.toKString()
    var pathLinked = true
    try {
        val status = alloc<platform.posix.stat>()
        check(fstat(descriptor, status.ptr) == 0) {
            "Could not inspect staging file permissions"
        }
        check(status.st_mode.toInt() and FILE_PERMISSION_MASK == OWNER_READ_WRITE) {
            "Staging file does not use owner-only permissions"
        }
        check(unlink(pathString) == 0) {
            "Could not unlink staging file"
        }
        pathLinked = false
        ApplePrivateTemporaryStorage(descriptor)
    } catch (e: Throwable) {
        if (pathLinked) {
            unlink(pathString)
        }
        close(descriptor)
        throw e
    }
}

@OptIn(ExperimentalForeignApi::class)
private class ApplePrivateTemporaryStorage(
    private val descriptor: Int,
) : PrivateTemporaryStorage {
    private val writableSink = FileDescriptorRawSink(descriptor)
    private var sinkClaimed = false
    private var sealed = false
    private var closed = false

    override fun sink(): RawSink {
        check(!closed) { "Staging file is closed" }
        check(!sealed) { "Staging file is sealed" }
        check(!sinkClaimed) { "Staging file sink has already been acquired" }
        sinkClaimed = true
        return writableSink
    }

    override fun sealForReading() {
        check(!closed) { "Staging file is closed" }
        check(!sealed) { "Staging file is already sealed" }
        writableSink.close()
        sealed = true
    }

    override fun source(): RawSource {
        check(!closed) { "Staging file is closed" }
        check(sealed) { "Staging file must be sealed before reading" }
        return FileDescriptorRawSource(descriptor)
    }

    override fun close() {
        if (closed) return
        closed = true
        closeAppleStagingResources(
            closeSink = writableSink::close,
            closeDescriptor = { close(descriptor) == 0 },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private class FileDescriptorRawSink(
    private val descriptor: Int,
) : RawSink {
    private val buffer = ByteArray(STAGING_BUFFER_BYTES)
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(!closed) { "Staging sink is closed" }
        require(byteCount >= 0 && byteCount <= source.size) {
            "Invalid staging write size"
        }

        var remaining = byteCount
        while (remaining > 0) {
            val requested = minOf(remaining, buffer.size.toLong()).toInt()
            val read = source.readAtMostTo(buffer, startIndex = 0, endIndex = requested)
            check(read > 0) { "Staging source ended early" }
            try {
                writeFully(descriptor, buffer, read)
            } finally {
                buffer.fill(0, fromIndex = 0, toIndex = read)
            }
            remaining -= read
        }
    }

    override fun flush() = Unit

    override fun close() {
        if (closed) return
        closed = true
        buffer.fill(0)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class FileDescriptorRawSource(
    private val descriptor: Int,
) : RawSource {
    private val buffer = ByteArray(STAGING_BUFFER_BYTES)
    private var position = 0L
    private var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Staging source is closed" }
        require(byteCount >= 0) { "Invalid staging read size" }
        if (byteCount == 0L) return 0

        val requested = minOf(byteCount, buffer.size.toLong()).toInt()
        val read = readAtMost(
            descriptor = descriptor,
            buffer = buffer,
            byteCount = requested,
            position = position,
        )
        if (read == 0) return -1
        position += read
        try {
            sink.write(buffer, startIndex = 0, endIndex = read)
        } finally {
            buffer.fill(0, fromIndex = 0, toIndex = read)
        }
        return read.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        buffer.fill(0)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeFully(
    descriptor: Int,
    buffer: ByteArray,
    byteCount: Int,
) {
    var offset = 0
    while (offset < byteCount) {
        val written = buffer.usePinned { pinned ->
            var result: Long
            do {
                result = posixWrite(
                    descriptor,
                    pinned.addressOf(offset),
                    (byteCount - offset).convert(),
                )
            } while (result < 0 && errno == EINTR)
            result
        }
        if (written <= 0) {
            throw IOException("Could not write staging file")
        }
        offset += written.toInt()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAtMost(
    descriptor: Int,
    buffer: ByteArray,
    byteCount: Int,
    position: Long,
): Int {
    val read = buffer.usePinned { pinned ->
        var result: Long
        do {
            result = posixPread(
                descriptor,
                pinned.addressOf(0),
                byteCount.convert(),
                position,
            )
        } while (result < 0 && errno == EINTR)
        result
    }
    if (read < 0) {
        throw IOException("Could not read staging file")
    }
    return read.toInt()
}

private const val STAGING_BUFFER_BYTES = 64 * 1024
private const val FILE_PERMISSION_MASK = 0x1FF
private const val OWNER_READ_WRITE = 0x180
