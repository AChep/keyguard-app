package com.artemchep.keyguard.util.zip

import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * A sequential, streaming reader of an archive; no random access.
 * Invalid content encountered while decoding headers or entry data throws
 * [ZipException]. Source I/O failures remain I/O exceptions.
 */
interface ZipReader : AutoCloseable {
    /**
     * Advances to the next entry, invalidating the previous one's source, or
     * returns `null` when the archive is exhausted.
     */
    fun nextEntry(): ZipReaderEntry?
}

/**
 * Opens [source] as an archive, taking ownership of it. An empty or null
 * [password] means "no password".
 * A nonempty password requires every entry read to use authenticated AES encryption.
 *
 * @throws ZipException when the archive content is invalid.
 * @throws kotlinx.io.IOException when the source cannot be read.
 */
@Suppress("FunctionName")
expect fun ZipReader(
    source: Source,
    password: String? = null,
): ZipReader

/**
 * [source] stays valid until the next [ZipReader.nextEntry] or
 * [ZipReader.close]; closing it does not close the reader.
 */
class ZipReaderEntry(
    val name: String,
    val source: Source,
)

/**
 * Opens [source] as an archive, like [ZipReader], bounding the work a
 * hostile archive can cause: [ZipReader.nextEntry] refuses an entry past the
 * first [maxEntries], and an entry's source refuses to yield more than
 * [maxEntryBytes]. Only what the caller reads counts towards the latter;
 * skipping an entry does not.
 *
 * @throws ZipArchiveException with [ZipArchiveError.TooManyEntries] or
 * [ZipArchiveError.TooLarge] once a limit is exceeded.
 */
@Suppress("FunctionName")
fun ZipReader(
    source: Source,
    password: String?,
    maxEntries: Int,
    maxEntryBytes: Long = Long.MAX_VALUE,
): ZipReader = LimitedZipReader(
    reader = ZipReader(source, password),
    maxEntries = maxEntries,
    maxEntryBytes = maxEntryBytes,
)

private class LimitedZipReader(
    private val reader: ZipReader,
    private val maxEntries: Int,
    private val maxEntryBytes: Long,
) : ZipReader by reader {
    private var count = 0

    override fun nextEntry(): ZipReaderEntry? {
        val entry = reader.nextEntry()
            ?: return null
        if (++count > maxEntries) {
            throw ZipArchiveException(ZipArchiveError.TooManyEntries)
        }
        return entry.takeIf { maxEntryBytes == Long.MAX_VALUE }
            ?: ZipReaderEntry(
                name = entry.name,
                source = ZipLimitedSource(
                    upstream = entry.source,
                    maxBytes = maxEntryBytes,
                ).buffered(),
            )
    }
}
