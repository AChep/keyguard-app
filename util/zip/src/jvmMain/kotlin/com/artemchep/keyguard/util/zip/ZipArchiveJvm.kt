package com.artemchep.keyguard.util.zip

import kotlinx.io.RawSource
import kotlinx.io.asSource
import net.lingala.zip4j.exception.ZipException as Zip4jException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.model.FileHeader
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException

internal actual fun openZipArchiveEntry(bytes: ByteArray, entry: ZipArchiveEntry, password: String?): RawSource {
    val chars = password?.toCharArray()
    val stream = ZipInputStream(ByteArrayInputStream(bytes, entry.offset, bytes.size - entry.offset), chars)
    try {
        val header = FileHeader().apply {
            compressedSize = entry.compressedSize
            uncompressedSize = entry.size
            crc = entry.crc
            isDirectory = entry.isDirectory
        }
        stream.getNextEntry(header, false) ?: throw ZipArchiveException(ZipArchiveError.Invalid)
    } catch (e: IOException) {
        stream.close()
        chars?.fill('\u0000')
        throw archiveFailure(e)
    }
    return object : FilterInputStream(stream) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            stream.read(b, off, len)
        } catch (e: IOException) {
            throw archiveFailure(e)
        }
        override fun close() {
            try { super.close() } finally { chars?.fill('\u0000') }
        }
    }.asSource()
}

private fun archiveFailure(e: Exception): ZipArchiveException = if (e is ZipArchiveException) e else {
    val reason = if (e is Zip4jException && e.type == Zip4jException.Type.WRONG_PASSWORD) {
        ZipArchiveError.Password
    } else {
        ZipArchiveError.Invalid
    }
    ZipArchiveException(reason, e)
}
