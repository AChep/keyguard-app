package com.artemchep.keyguard.util.zip

import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import net.lingala.zip4j.io.inputstream.ZipInputStream
import java.io.FilterInputStream
import java.io.InputStream

@Suppress("FunctionName")
actual fun ZipReader(
    source: Source,
    password: String?,
): ZipReader = ZipReaderJvm(
    source = source,
    password = password,
)

private class ZipReaderJvm(
    source: Source,
    password: String?,
) : ZipReader {
    private val zipStream = createZipStream(
        inputStream = source.asInputStream(),
        password = password,
    )

    private var currentEntry: ZipEntryInputStream? = null

    override fun nextEntry(): ZipReaderEntry? {
        // Otherwise the previous entry's source would hand out the new
        // entry's bytes.
        invalidateCurrentEntry()

        val header = zipStream.nextEntry
            ?: return null
        // zip4j ends the stream at the end of the current entry.
        val entryStream = ZipEntryInputStream(zipStream)
        currentEntry = entryStream
        return ZipReaderEntry(
            name = header.fileName,
            source = entryStream.asSource().buffered(),
        )
    }

    override fun close() {
        invalidateCurrentEntry()
        zipStream.close()
    }

    private fun invalidateCurrentEntry() {
        currentEntry?.invalidate()
        currentEntry = null
    }
}

private fun createZipStream(
    inputStream: InputStream,
    password: String?,
): ZipInputStream {
    val chars = password
        ?.takeIf { it.isNotEmpty() }
        ?.toCharArray()
    return if (chars != null) {
        ZipInputStream(inputStream, chars)
    } else {
        ZipInputStream(inputStream)
    }
}

/**
 * The bytes of the archive's current entry. `close` is a no-op so closing an
 * entry leaves the reader usable.
 */
private class ZipEntryInputStream(
    inputStream: InputStream,
) : FilterInputStream(inputStream) {
    private var valid = true

    fun invalidate() {
        valid = false
    }

    override fun read(): Int {
        checkValid()
        return super.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkValid()
        return super.read(b, off, len)
    }

    override fun skip(n: Long): Long {
        checkValid()
        return super.skip(n)
    }

    override fun close() = Unit

    private fun checkValid() {
        if (!valid) {
            throw ZipException("The archive has already moved past this entry")
        }
    }
}
