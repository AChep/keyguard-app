package com.artemchep.keyguard.crypto

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardOpenOption.DELETE_ON_CLOSE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource

internal fun createPrivateTemporaryStorageJvm(
    directory: File?,
): PrivateTemporaryStorage {
    val file = createPrivateTemporaryFile(directory)
    return try {
        JvmPrivateTemporaryStorage.open(file)
    } catch (e: Throwable) {
        file.delete()
        throw e
    }
}

internal fun createPrivateTemporaryFile(
    directory: File? = null,
): File = createPrivateTemporaryFilePlatform(directory)

internal expect fun createPrivateTemporaryFilePlatform(
    directory: File?,
): File

internal fun createPrivateTemporaryFileJvm(
    directory: File? = null,
): File {
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        val permissions = PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE_PERMISSIONS)
        val path = if (directory != null) {
            Files.createTempFile(directory.toPath(), PRIVATE_TEMPORARY_FILE_PREFIX, ".tmp", permissions)
        } else {
            Files.createTempFile(PRIVATE_TEMPORARY_FILE_PREFIX, ".tmp", permissions)
        }
        return path.toFile()
    }

    val file = File.createTempFile(PRIVATE_TEMPORARY_FILE_PREFIX, ".tmp", directory)
    val hardened = file.setReadable(false, false) &&
        file.setWritable(false, false) &&
        file.setExecutable(false, false) &&
        file.setReadable(true, true) &&
        file.setWritable(true, true)
    if (!hardened) {
        file.delete()
        error("Could not restrict plaintext staging file permissions")
    }
    return file
}

private class JvmPrivateTemporaryStorage(
    private val file: File,
    private val channel: FileChannel,
) : PrivateTemporaryStorage {
    private val writableSink = FileChannelRawSink(channel)
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
        return FileChannelRawSource(channel)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            writableSink.close()
        } finally {
            try {
                channel.close()
            } finally {
                file.delete()
            }
        }
    }

    companion object {
        fun open(file: File): JvmPrivateTemporaryStorage {
            val channel = FileChannel.open(file.toPath(), READ, WRITE, DELETE_ON_CLOSE)
            // POSIX removes the directory entry immediately. Other providers retain it until close.
            runCatching { Files.deleteIfExists(file.toPath()) }
            return JvmPrivateTemporaryStorage(file, channel)
        }
    }
}

private class FileChannelRawSink(
    private val channel: FileChannel,
) : RawSink {
    private val buffer = ByteArray(STAGING_BUFFER_BYTES)
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(!closed) { "OpenPGP plaintext staging sink is closed" }
        require(byteCount >= 0L && byteCount <= source.size) {
            "Invalid OpenPGP plaintext staging write size"
        }

        var remaining = byteCount
        while (remaining > 0L) {
            val requested = minOf(remaining, buffer.size.toLong()).toInt()
            val read = source.readAtMostTo(buffer, startIndex = 0, endIndex = requested)
            check(read > 0) { "OpenPGP plaintext staging source ended early" }
            try {
                val byteBuffer = ByteBuffer.wrap(buffer, 0, read)
                while (byteBuffer.hasRemaining()) {
                    if (channel.write(byteBuffer) <= 0) {
                        throw IOException("Could not write OpenPGP plaintext staging file")
                    }
                }
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

private class FileChannelRawSource(
    private val channel: FileChannel,
) : RawSource {
    private val buffer = ByteArray(STAGING_BUFFER_BYTES)
    private var position = 0L
    private var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Staging source is closed" }
        require(byteCount >= 0L) { "Invalid staging read size" }
        if (byteCount == 0L) return 0L

        val requested = minOf(byteCount, buffer.size.toLong()).toInt()
        val read = channel.read(ByteBuffer.wrap(buffer, 0, requested), position)
        if (read < 0) return -1L
        if (read == 0) return 0L
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

private val OWNER_ONLY_FILE_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
)

private const val STAGING_BUFFER_BYTES = 64 * 1024

private const val PRIVATE_TEMPORARY_FILE_PREFIX = "keyguard-private-"
