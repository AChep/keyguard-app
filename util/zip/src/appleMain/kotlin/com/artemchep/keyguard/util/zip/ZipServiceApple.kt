package com.artemchep.keyguard.util.zip

import com.artemchep.keyguard.util.zip.bridge.NATIVE_ZIP_ABI_VERSION
import com.artemchep.keyguard.util.zip.bridge.NATIVE_ZIP_STATUS_SUCCESS
import com.artemchep.keyguard.util.zip.bridge.NativeZip
import com.artemchep.keyguard.util.zip.bridge.isNativeZipFailure
import com.artemchep.keyguard.util.zip.bridge.nativeZipFailureException
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid

/**
 * The Rust backed [ZipService] of the Apple targets. The native writer needs a
 * seekable file, so the archive is assembled in a temporary file and then
 * streamed into the caller's sink.
 */
internal class ZipServiceApple : ZipService {
    override suspend fun zip(
        outputStream: Sink,
        config: ZipConfig,
        entries: List<ZipEntry>,
    ) {
        NativeZipAbi.ensureCompatible()
        val path = Path(SystemTemporaryDirectory, "keyguard-zip-${Uuid.random()}.zip")
        try {
            writeArchive(
                path = path,
                config = config,
                entries = entries,
            )
            SystemFileSystem.source(path).buffered().use { source ->
                outputStream.transferFrom(source)
            }
            outputStream.flush()
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    private suspend fun writeArchive(
        path: Path,
        config: ZipConfig,
        entries: List<ZipEntry>,
    ) {
        val handle = requireNativeZipHandle(
            NativeZip.open(
                path = path.toString(),
                password = config.encryption?.password.orEmpty(),
            ),
        )
        var finished = false
        try {
            entries.forEach { entry ->
                writeEntry(
                    handle = handle,
                    entry = entry,
                )
            }
            // `finish` consumes the handle even when it fails.
            finished = true
            checkNativeZipStatus(NativeZip.finish(handle))
        } finally {
            if (!finished) {
                // The original failure is the one worth reporting.
                NativeZip.abort(handle)
            }
        }
    }

    private suspend fun writeEntry(
        handle: Long,
        entry: ZipEntry,
    ) {
        checkNativeZipStatus(NativeZip.beginEntry(handle, entry.name))
        val entrySink = NativeZipRawSink(handle)
        when (val data = entry.data) {
            is ZipEntry.Data.In -> data.stream().use { source ->
                source.transferTo(entrySink)
            }

            is ZipEntry.Data.Out -> {
                val sink = entrySink.buffered()
                data.stream(sink)
                sink.flush()
            }
        }
        checkNativeZipStatus(NativeZip.endEntry(handle))
    }
}

actual fun createZipService(): ZipService = ZipServiceApple()

internal const val COPY_BUFFER_SIZE: Int = 64 * 1024

internal object NativeZipAbi {
    @Volatile
    private var verified = false

    fun ensureCompatible() {
        if (verified) return
        val actual = NativeZip.abiVersion()
        if (actual != NATIVE_ZIP_ABI_VERSION) {
            throw ZipException(
                "Unsupported native zip ABI $actual; expected $NATIVE_ZIP_ABI_VERSION",
            )
        }
        verified = true
    }
}

/**
 * Streams into the archive's current entry. `flush` and `close` are no-ops;
 * the entry is closed by the caller through `endEntry`.
 */
private class NativeZipRawSink(
    private val handle: Long,
) : RawSink {
    private val buffer = ByteArray(COPY_BUFFER_SIZE)

    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0L) {
            "byteCount ($byteCount) must not be negative"
        }
        var remaining = byteCount
        while (remaining > 0L) {
            val chunk = minOf(remaining, buffer.size.toLong()).toInt()
            val read = source.readAtMostTo(buffer, 0, chunk)
            if (read <= 0) {
                throw ZipException("Archive entry ran out of bytes before $byteCount were written")
            }
            checkNativeZipStatus(NativeZip.write(handle, buffer, 0, read))
            remaining -= read.toLong()
        }
    }

    override fun flush() = Unit

    override fun close() = Unit
}

internal fun requireNativeZipHandle(handle: Long): Long {
    if (isNativeZipFailure(handle)) {
        throw nativeZipFailureException(handle)
    }
    if (handle <= 0L) {
        throw ZipException("Native zip returned an invalid archive handle $handle")
    }
    return handle
}

internal fun checkNativeZipStatus(status: Long) {
    if (isNativeZipFailure(status)) {
        throw nativeZipFailureException(status)
    }
    if (status != NATIVE_ZIP_STATUS_SUCCESS) {
        throw ZipException("Native zip returned an unexpected status $status")
    }
}
