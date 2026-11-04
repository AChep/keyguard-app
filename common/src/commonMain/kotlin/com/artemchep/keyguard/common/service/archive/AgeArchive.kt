package com.artemchep.keyguard.common.service.archive

import com.artemchep.keyguard.crypto.PrivateTemporaryDirProvider
import com.artemchep.keyguard.util.age.AgeException
import com.artemchep.keyguard.util.age.AgeFailure
import com.artemchep.keyguard.util.age.AgePassword
import com.artemchep.keyguard.util.age.AgeSecret
import com.artemchep.keyguard.util.age.AgeZipReader
import com.artemchep.keyguard.util.age.AgeZipWriter
import com.artemchep.keyguard.util.zip.ZipArchiveError
import com.artemchep.keyguard.util.zip.ZipArchiveException
import com.artemchep.keyguard.util.zip.ZipEntry
import com.artemchep.keyguard.util.zip.ZipPasswordException
import com.artemchep.keyguard.util.zip.ZipReader
import com.artemchep.keyguard.util.zip.ZipReaderEntry
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

private val AGE_MAGIC = "age-encryption.org/".encodeToByteArray()

internal fun Source.isAgeArchive(): Boolean = peek().use { peek ->
    peek.request(AGE_MAGIC.size.toLong()) && peek.readByteArray(AGE_MAGIC.size).contentEquals(AGE_MAGIC)
}

internal suspend fun writeAgeArchive(output: Sink, secret: AgeSecret, entries: List<ZipEntry>) {
    AgeZipWriter(output, secret).use { writer ->
        entries.forEach { entry ->
            writer.entry(entry.name) { sink ->
                when (val data = entry.data) {
                    is ZipEntry.Data.In -> data.stream().use { source -> source.transferTo(sink) }
                    is ZipEntry.Data.Out -> data.stream(sink)
                }
            }
        }
        writer.finish()
    }
}

/**
 * Adapts age authentication failures to the existing archive/import error contract.
 * Ciphertext spools into the private temporary directory, the root swept for scratch leftovers.
 */
internal fun readAgeArchive(
    source: Source,
    secret: AgeSecret?,
    maxEntryBytes: Long = Long.MAX_VALUE,
    checkCancellation: () -> Unit = {},
): ZipReader {
    if (secret == null || secret is AgePassword && secret.value.isEmpty()) {
        source.close()
        throw ZipPasswordException()
    }
    val reader = ageArchiveCall {
        // The reader owns the source, so release it if the directory is unavailable.
        val directory = runCatching { PrivateTemporaryDirProvider.Default.get().value }
            .onFailure { source.close() }
            .getOrThrow()
        AgeZipReader(
            source,
            secret,
            spoolDirectory = directory,
            maxEntryBytes = maxEntryBytes,
            checkCancellation = checkCancellation,
        )
    }
    return object : ZipReader {
        override fun nextEntry(): ZipReaderEntry? = ageArchiveCall {
            reader.nextEntry()?.let { entry ->
                val source = object : RawSource {
                    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = ageArchiveCall {
                        entry.source.readAtMostTo(sink, byteCount)
                    }
                    override fun close() = entry.source.close()
                }.buffered()
                ZipReaderEntry(entry.name, source)
            }
        }
        override fun close() = reader.close()
    }
}

private inline fun <T> ageArchiveCall(block: () -> T): T = try {
    block()
} catch (e: AgeException) {
    throw when (e.reason) {
        AgeFailure.Password -> ZipPasswordException()
        AgeFailure.Limit -> ZipArchiveException(ZipArchiveError.TooLarge, e)
        AgeFailure.Corrupt -> ZipArchiveException(ZipArchiveError.Invalid, e)
        else -> e
    }
}
