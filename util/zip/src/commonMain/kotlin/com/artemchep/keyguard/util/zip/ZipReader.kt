package com.artemchep.keyguard.util.zip

import kotlinx.io.Source

/** A sequential, streaming reader of an archive; no random access. */
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
 *
 * @throws ZipException when the archive cannot be opened.
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
