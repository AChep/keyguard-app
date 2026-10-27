package com.artemchep.keyguard.util.zip

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

/** An in-memory, indexed archive. Owns a copy of [bytes] and at most one open entry. */
class ZipArchive(bytes: ByteArray, maxEntries: Int = 10_000) : AutoCloseable {
    private val bytes = bytes.copyOf()
    val entries: List<ZipArchiveEntry> = try {
        readZipDirectory(this.bytes, maxEntries)
    } catch (e: ZipException) {
        this.bytes.fill(0)
        throw e
    }
    private var closed = false
    private var current: Source? = null

    /** Opening another entry closes the preceding source. Read to EOF to verify integrity. */
    @Suppress("ThrowsCount") // Distinct public failure reasons are part of the archive API.
    fun openEntry(id: Int, password: String? = null, maxBytes: Long): Source {
        check(!closed) { "Archive is closed" }
        val entry = entries.getOrNull(id) ?: throw ZipArchiveException(ZipArchiveError.Invalid)
        if (!entry.isSupported || entry.isDirectory) throw ZipArchiveException(ZipArchiveError.Unsupported)
        if (entry.size > maxBytes) throw ZipArchiveException(ZipArchiveError.TooLarge)
        if (entry.isEncrypted && password == null) throw ZipArchiveException(ZipArchiveError.Password)
        current?.close()
        current = null
        val source = openZipArchiveEntry(bytes, entry, password)
        val limited = object : RawSource {
            private var total = 0L
            @Suppress("ThrowsCount") // Verify both advertised size and the caller's independent limit.
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                val remaining = minOf(maxBytes, entry.size) - total
                val read = source.readAtMostTo(sink, minOf(byteCount, remaining + 1))
                if (read == -1L) {
                    if (total != entry.size) throw ZipArchiveException(ZipArchiveError.Invalid)
                } else {
                    total += read
                    if (total > maxBytes) throw ZipArchiveException(ZipArchiveError.TooLarge)
                    if (total > entry.size) throw ZipArchiveException(ZipArchiveError.Invalid)
                }
                return read
            }
            override fun close() = source.close()
        }.buffered()
        current = limited
        return limited
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            current?.close()
        } finally {
            current = null
            bytes.fill(0)
        }
    }
}

@ConsistentCopyVisibility
data class ZipArchiveEntry internal constructor(
    val id: Int,
    val path: String,
    val isDirectory: Boolean,
    val compressedSize: Long,
    val size: Long,
    val isEncrypted: Boolean,
    val isSupported: Boolean,
    val isSafe: Boolean,
    internal val offset: Int,
    internal val crc: Long,
)

enum class ZipArchiveError { Invalid, Unsupported, Password, TooLarge, TooManyEntries }

class ZipArchiveException(val reason: ZipArchiveError, cause: Throwable? = null) :
    ZipException("Archive operation failed: $reason", cause)

internal expect fun openZipArchiveEntry(bytes: ByteArray, entry: ZipArchiveEntry, password: String?): RawSource
