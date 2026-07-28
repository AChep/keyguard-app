package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.windows.WindowsOwnerOnlySecurityAttributes
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val GENERIC_READ = 0x80000000.toInt()
private const val GENERIC_WRITE = 0x40000000
private const val DELETE_ACCESS = 0x00010000
private const val FILE_SHARE_READ = 0x00000001
private const val FILE_SHARE_WRITE = 0x00000002
private const val FILE_SHARE_DELETE = 0x00000004
private const val CREATE_NEW = 1
private const val FILE_ATTRIBUTE_TEMPORARY = 0x00000100
private const val FILE_FLAG_DELETE_ON_CLOSE = 0x04000000
private const val FILE_BEGIN = 0

private const val ERROR_HANDLE_EOF = 38
private const val ERROR_FILE_EXISTS = 80
private const val ERROR_ALREADY_EXISTS = 183

private const val INVALID_HANDLE_VALUE = -1L
private const val WINDOWS_STAGING_BUFFER_BYTES = 64 * 1024
private const val MAX_TEMPORARY_FILE_ATTEMPTS = 128
private const val PRIVATE_TEMPORARY_FILE_PREFIX = "keyguard-private-"

internal fun createWindowsPrivateTemporaryStorage(
    directory: File? = null,
): PrivateTemporaryStorage {
    val created = createWindowsPrivateTemporaryFileHandle(
        directory = directory,
        deleteOnClose = true,
        exclusive = true,
    )
    return WindowsPrivateTemporaryStorage(
        path = created.path,
        handle = created.handle,
    )
}

internal fun createWindowsPrivateTemporaryFile(
    directory: File? = null,
): File {
    val created = createWindowsPrivateTemporaryFileHandle(
        directory = directory,
        deleteOnClose = false,
        exclusive = false,
    )
    if (!WindowsTemporaryFileKernel32.INSTANCE.CloseHandle(created.handle)) {
        val error = Native.getLastError()
        runCatching { Files.deleteIfExists(created.path) }
        throw windowsTemporaryFileException("CloseHandle", error)
    }
    return created.path.toFile()
}

private fun createWindowsPrivateTemporaryFileHandle(
    directory: File?,
    deleteOnClose: Boolean,
    exclusive: Boolean,
): WindowsTemporaryFileHandle {
    val directoryPath = (directory?.toPath() ?: Path.of(System.getProperty("java.io.tmpdir")))
        .toAbsolutePath()
        .normalize()
    Files.createDirectories(directoryPath)
    check(Files.isDirectory(directoryPath)) {
        "Private temporary storage requires a directory"
    }

    WindowsOwnerOnlySecurityAttributes.create().use { securityAttributes ->
        repeat(MAX_TEMPORARY_FILE_ATTEMPTS) {
            val path = directoryPath.resolve(
                "$PRIVATE_TEMPORARY_FILE_PREFIX${UUID.randomUUID()}.tmp",
            )
            val shareMode = if (exclusive) {
                0
            } else {
                FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE
            }
            val flags = FILE_ATTRIBUTE_TEMPORARY or if (deleteOnClose) {
                FILE_FLAG_DELETE_ON_CLOSE
            } else {
                0
            }
            val handle = WindowsTemporaryFileKernel32.INSTANCE.CreateFileW(
                WString(path.toCreateFilePath()),
                GENERIC_READ or GENERIC_WRITE or DELETE_ACCESS,
                shareMode,
                securityAttributes.pointer,
                CREATE_NEW,
                flags,
                null,
            )
            if (Pointer.nativeValue(handle) != INVALID_HANDLE_VALUE) {
                return WindowsTemporaryFileHandle(
                    path = path,
                    handle = handle,
                )
            }

            val error = Native.getLastError()
            if (error != ERROR_FILE_EXISTS && error != ERROR_ALREADY_EXISTS) {
                throw windowsTemporaryFileException("CreateFileW", error)
            }
        }
    }

    throw IOException("Could not allocate a unique private temporary file")
}

private data class WindowsTemporaryFileHandle(
    val path: Path,
    val handle: Pointer,
)

internal class WindowsPrivateTemporaryStorage(
    internal val path: Path,
    private val handle: Pointer,
) : PrivateTemporaryStorage {
    private val lifecycleLock = Any()
    private val writableSink = WindowsPrivateTemporaryStorageSink(this)
    private var sinkClaimed = false
    private var sealed = false
    private var closed = false

    override fun sink(): RawSink = synchronized(lifecycleLock) {
        check(!closed) { "Staging file is closed" }
        check(!sealed) { "Staging file is sealed" }
        check(!sinkClaimed) { "Staging file sink has already been acquired" }
        sinkClaimed = true
        writableSink
    }

    override fun sealForReading() = synchronized(lifecycleLock) {
        check(!closed) { "Staging file is closed" }
        check(!sealed) { "Staging file is already sealed" }
        writableSink.close()
        sealed = true
    }

    override fun source(): RawSource = synchronized(lifecycleLock) {
        check(!closed) { "Staging file is closed" }
        check(sealed) { "Staging file must be sealed before reading" }
        WindowsPrivateTemporaryStorageSource(this)
    }

    internal fun write(
        buffer: ByteArray,
        byteCount: Int,
    ) = synchronized(lifecycleLock) {
        check(!closed) { "Staging file is closed" }
        check(!sealed) { "Staging file is sealed" }

        var remaining = byteCount
        while (remaining > 0) {
            val written = IntByReference()
            val ok = WindowsTemporaryFileKernel32.INSTANCE.WriteFile(
                handle,
                buffer,
                remaining,
                written,
                null,
            )
            if (!ok) {
                throw windowsTemporaryFileException("WriteFile", Native.getLastError())
            }
            val count = written.value
            if (count <= 0 || count > remaining) {
                throw IOException("WriteFile returned an invalid byte count")
            }
            remaining -= count
            if (remaining > 0) {
                buffer.copyInto(
                    destination = buffer,
                    destinationOffset = 0,
                    startIndex = count,
                    endIndex = count + remaining,
                )
            }
        }
    }

    internal fun read(
        position: Long,
        buffer: ByteArray,
        byteCount: Int,
    ): Int = synchronized(lifecycleLock) {
        check(!closed) { "Staging file is closed" }
        check(sealed) { "Staging file must be sealed before reading" }

        val positioned = WindowsTemporaryFileKernel32.INSTANCE.SetFilePointerEx(
            handle,
            position,
            null,
            FILE_BEGIN,
        )
        if (!positioned) {
            throw windowsTemporaryFileException("SetFilePointerEx", Native.getLastError())
        }

        val bytesRead = IntByReference()
        val ok = WindowsTemporaryFileKernel32.INSTANCE.ReadFile(
            handle,
            buffer,
            byteCount,
            bytesRead,
            null,
        )
        if (!ok) {
            val error = Native.getLastError()
            if (error == ERROR_HANDLE_EOF) return@synchronized -1
            throw windowsTemporaryFileException("ReadFile", error)
        }
        val count = bytesRead.value
        if (count < 0 || count > byteCount) {
            throw IOException("ReadFile returned an invalid byte count")
        }
        count.takeIf { it > 0 } ?: -1
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            writableSink.close()
        }

        if (!WindowsTemporaryFileKernel32.INSTANCE.CloseHandle(handle)) {
            throw windowsTemporaryFileException("CloseHandle", Native.getLastError())
        }
    }
}

private class WindowsPrivateTemporaryStorageSink(
    private val storage: WindowsPrivateTemporaryStorage,
) : RawSink {
    private val buffer = ByteArray(WINDOWS_STAGING_BUFFER_BYTES)
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        check(!closed) { "Private temporary storage sink is closed" }
        require(byteCount >= 0L && byteCount <= source.size) {
            "Invalid private temporary storage write size"
        }

        var remaining = byteCount
        while (remaining > 0L) {
            val requested = minOf(remaining, buffer.size.toLong()).toInt()
            val read = source.readAtMostTo(buffer, startIndex = 0, endIndex = requested)
            check(read > 0) { "Private temporary storage source ended early" }
            try {
                storage.write(buffer, read)
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

private class WindowsPrivateTemporaryStorageSource(
    private val storage: WindowsPrivateTemporaryStorage,
) : RawSource {
    private val buffer = ByteArray(WINDOWS_STAGING_BUFFER_BYTES)
    private var position = 0L
    private var closed = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed) { "Private temporary storage source is closed" }
        require(byteCount >= 0L) { "Invalid private temporary storage read size" }
        if (byteCount == 0L) return 0L

        val requested = minOf(byteCount, buffer.size.toLong()).toInt()
        val read = storage.read(position, buffer, requested)
        if (read < 0) return -1L
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

private fun windowsTemporaryFileException(
    functionName: String,
    error: Int,
): IOException = IOException("$functionName failed with Windows error $error")

private fun Path.toCreateFilePath(): String {
    val path = toString()
    if (path.startsWith("\\\\?\\")) return path
    return if (path.startsWith("\\\\")) {
        "\\\\?\\UNC\\${path.removePrefix("\\\\")}"
    } else {
        "\\\\?\\$path"
    }
}

@Suppress("FunctionName")
private interface WindowsTemporaryFileKernel32 : StdCallLibrary {
    companion object {
        val INSTANCE: WindowsTemporaryFileKernel32 by lazy {
            Native.load(
                "kernel32",
                WindowsTemporaryFileKernel32::class.java,
            ) as WindowsTemporaryFileKernel32
        }
    }

    fun CreateFileW(
        lpFileName: WString,
        dwDesiredAccess: Int,
        dwShareMode: Int,
        lpSecurityAttributes: Pointer?,
        dwCreationDisposition: Int,
        dwFlagsAndAttributes: Int,
        hTemplateFile: Pointer?,
    ): Pointer

    fun ReadFile(
        hFile: Pointer,
        lpBuffer: ByteArray,
        nNumberOfBytesToRead: Int,
        lpNumberOfBytesRead: IntByReference,
        lpOverlapped: Pointer?,
    ): Boolean

    fun WriteFile(
        hFile: Pointer,
        lpBuffer: ByteArray,
        nNumberOfBytesToWrite: Int,
        lpNumberOfBytesWritten: IntByReference,
        lpOverlapped: Pointer?,
    ): Boolean

    fun SetFilePointerEx(
        hFile: Pointer,
        liDistanceToMove: Long,
        lpNewFilePointer: Pointer?,
        dwMoveMethod: Int,
    ): Boolean

    fun CloseHandle(
        hObject: Pointer,
    ): Boolean
}
