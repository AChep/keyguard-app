package com.artemchep.keyguard.util.zip

import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import net.lingala.zip4j.exception.ZipException as Zip4jException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.model.LocalFileHeader
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

@Suppress("FunctionName")
actual fun ZipReader(
    source: Source,
    password: String?,
): ZipReader = ZipReaderJvm(
    source = source,
    // An empty password means "no password".
    password = password?.takeIf { it.isNotEmpty() },
)

private class ZipReaderJvm(
    source: Source,
    password: String?,
) : ZipReader {
    private val requireEncryption = password != null
    private var encryptionRejected = false

    private val zipStream = createZipStream(
        inputStream = ZipSourceInputStream(source.asInputStream()),
        password = password,
    )

    private var currentEntry: ZipEntryInputStream? = null

    override fun nextEntry(): ZipReaderEntry? {
        if (encryptionRejected) throw ZipEncryptionRequiredException()
        // Otherwise the previous entry's source would hand out the new
        // entry's bytes.
        invalidateCurrentEntry()

        val header = readNextHeader()
            ?: return null
        if (requireEncryption &&
            (!header.isEncrypted || header.encryptionMethod != EncryptionMethod.AES)
        ) {
            // Retrying must not skip the rejected entry and resume the archive.
            encryptionRejected = true
            throw ZipEncryptionRequiredException()
        }
        // zip4j ends the stream at the end of the current entry.
        val entryStream = ZipEntryInputStream(zipStream)
        currentEntry = entryStream
        return ZipReaderEntry(
            name = header.fileName,
            source = entryStream.asSource().buffered(),
        )
    }

    private fun readNextHeader(): LocalFileHeader? = zipRead {
        zipStream.nextEntry
    }

    override fun close() {
        invalidateCurrentEntry()
        zipRead { zipStream.close() }
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
    val chars = password?.toCharArray()
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
        return zipRead { super.read() }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkValid()
        return zipRead { super.read(b, off, len) }
    }

    override fun skip(n: Long): Long {
        checkValid()
        return zipRead { super.skip(n) }
    }

    override fun close() = Unit

    private fun checkValid() {
        if (!valid) {
            throw ZipException("The archive has already moved past this entry")
        }
    }
}

/** Decoder I/O failures describe invalid bytes; source I/O failures describe storage. */
private inline fun <T> zipRead(block: () -> T): T = try {
    block()
} catch (e: ZipSourceException) {
    throw e.error
} catch (e: IOException) {
    throw if (e is Zip4jException && e.type == Zip4jException.Type.WRONG_PASSWORD) {
        ZipPasswordException(cause = e)
    } else {
        ZipException("The archive is invalid or corrupt", cause = e)
    }
}

/**
 * Keep source failures out of zip4j's IOException handlers, which may replace
 * them with password errors. Only [zipRead] unwraps this private marker.
 */
private class ZipSourceException(val error: IOException) : RuntimeException(error)

private class ZipSourceInputStream(
    private val delegate: InputStream,
) : InputStream() {
    override fun read(): Int = sourceRead { delegate.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = sourceRead { delegate.read(b, off, len) }

    override fun skip(n: Long): Long = sourceRead { delegate.skip(n) }

    override fun close() = sourceRead { delegate.close() }

    private inline fun <T> sourceRead(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw ZipSourceException(e)
    }
}
