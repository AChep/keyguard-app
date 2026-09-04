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

    override fun nextEntry(): ZipReaderEntry? {
        val header = zipStream.nextEntry
            ?: return null
        return ZipReaderEntry(
            name = header.fileName,
            // zip4j ends the stream at the end of the current entry.
            source = NonClosingInputStream(zipStream).asSource().buffered(),
        )
    }

    override fun close() {
        zipStream.close()
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

private class NonClosingInputStream(
    inputStream: InputStream,
) : FilterInputStream(inputStream) {
    override fun close() = Unit
}
