package com.artemchep.keyguard.util.zip

import com.artemchep.keyguard.util.zip.bridge.NATIVE_ZIP_END_OF_ARCHIVE
import com.artemchep.keyguard.util.zip.bridge.NATIVE_ZIP_MAX_ENTRY_NAME_BYTES
import com.artemchep.keyguard.util.zip.bridge.NativeZip
import com.artemchep.keyguard.util.zip.bridge.isNativeZipFailure
import com.artemchep.keyguard.util.zip.bridge.nativeZipFailureException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.uuid.Uuid

@Suppress("FunctionName")
actual fun ZipReader(
    source: Source,
    password: String?,
): ZipReader {
    NativeZipAbi.ensureCompatible()
    // The native reader needs a seekable file; the reader owns the spooled
    // copy and removes it on close.
    val path = Path(SystemTemporaryDirectory, "keyguard-zip-read-${Uuid.random()}.zip")
    var opened = false
    try {
        source.use { input ->
            SystemFileSystem.sink(path).buffered().use { sink ->
                sink.transferFrom(input)
            }
        }
        val reader = ZipReaderApple(
            handle = requireNativeZipHandle(
                NativeZip.readerOpen(
                    path = path.toString(),
                    password = password.orEmpty(),
                ),
            ),
            path = path,
        )
        opened = true
        return reader
    } finally {
        if (!opened) {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}

private class ZipReaderApple(
    private val handle: Long,
    private val path: Path,
) : ZipReader {
    private var closed = false

    private var currentEntry: NativeZipEntryRawSource? = null

    override fun nextEntry(): ZipReaderEntry? {
        checkOpen()
        // Otherwise the previous entry's source would hand out the new
        // entry's bytes.
        invalidateCurrentEntry()

        // Sized to the ABI limit, so BUFFER_TOO_SMALL cannot happen here.
        val nameBuffer = ByteArray(NATIVE_ZIP_MAX_ENTRY_NAME_BYTES)
        val length = NativeZip.readerNextEntry(handle, nameBuffer)
        if (length == NATIVE_ZIP_END_OF_ARCHIVE) {
            return null
        }
        if (isNativeZipFailure(length)) {
            throw nativeZipFailureException(length)
        }

        val entrySource = NativeZipEntryRawSource(handle)
        currentEntry = entrySource
        return ZipReaderEntry(
            name = nameBuffer.decodeToString(0, length.toInt()),
            source = entrySource.buffered(),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidateCurrentEntry()
        try {
            checkNativeZipStatus(NativeZip.readerClose(handle))
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    private fun checkOpen() {
        if (closed) {
            throw ZipException("The archive is closed")
        }
    }

    private fun invalidateCurrentEntry() {
        currentEntry?.invalidate()
        currentEntry = null
    }
}

/**
 * The bytes of the archive's current entry. `close` is a no-op so closing an
 * entry leaves the reader usable, as on the JVM.
 */
private class NativeZipEntryRawSource(
    private val handle: Long,
) : RawSource {
    private val buffer = ByteArray(COPY_BUFFER_SIZE)

    private var valid = true

    fun invalidate() {
        valid = false
    }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) {
            "byteCount ($byteCount) must not be negative"
        }
        if (!valid) {
            throw ZipException("The archive has already moved past this entry")
        }
        if (byteCount == 0L) {
            return 0L
        }
        val chunk = minOf(byteCount, buffer.size.toLong()).toInt()
        val read = NativeZip.readerRead(handle, buffer, 0, chunk)
        if (isNativeZipFailure(read)) {
            throw nativeZipFailureException(read)
        }
        return if (read == 0L) {
            -1L
        } else {
            sink.write(buffer, 0, read.toInt())
            read
        }
    }

    override fun close() = Unit
}
