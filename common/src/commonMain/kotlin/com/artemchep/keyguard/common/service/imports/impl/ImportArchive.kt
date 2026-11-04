package com.artemchep.keyguard.common.service.imports.impl

import com.artemchep.keyguard.util.age.AgePassword
import com.artemchep.keyguard.common.service.archive.isAgeArchive
import com.artemchep.keyguard.common.service.archive.readAgeArchive
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.util.zip.ZipArchiveError
import com.artemchep.keyguard.util.zip.ZipArchiveException
import com.artemchep.keyguard.util.zip.ZipPasswordException
import com.artemchep.keyguard.util.zip.ZipReader
import com.artemchep.keyguard.util.zip.ZipReaderEntry
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Bounds the work a hostile archive can cause.
 */
private const val MAX_ARCHIVE_ENTRIES = 100_000

private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/**
 * The archive is encrypted, and the password is missing or wrong.
 */
internal class ImportArchivePasswordException : RuntimeException()

/** The chosen import format only supports unencrypted archives. */
internal class ImportArchiveUnsupportedEncryptionException : RuntimeException()

/**
 * The names of every entry of an archive, and the bytes of those read.
 */
internal class ImportArchive(
    val names: List<String>,
    val entries: Map<String, ByteArray>,
) {
    /**
     * The first entry name per key, the key being what follows [prefix] up
     * to the next [delimiter]; names without both are left out. Built once,
     * so that finding the entry of each of thousands of attachments does not
     * search every name.
     */
    fun namesByKey(
        prefix: String,
        delimiter: Char,
    ): Map<String, String> {
        val index = mutableMapOf<String, String>()
        names.forEach { name ->
            if (!name.startsWith(prefix)) {
                return@forEach
            }
            val end = name.indexOf(delimiter, startIndex = prefix.length)
            if (end < 0) {
                return@forEach
            }
            index.getOrPut(name.substring(prefix.length, end)) { name }
        }
        return index
    }
}

/**
 * `true` if the file starts like a ZIP archive.
 */
internal fun ImportInput.isZipArchive(): Boolean = open().use { source ->
    val head = Buffer()
    source.readAtMostTo(head, ZIP_MAGIC.size.toLong())
    head.readByteArray().contentEquals(ZIP_MAGIC)
}

/**
 * Reads the entries of an archive whose name [accept]s, each up to
 * [IMPORT_TEXT_MAX_BYTES], and lists the names of all of them. The bytes of
 * every other entry are skipped, not kept.
 */
internal fun ImportInput.readArchive(
    password: Password?,
    allowAge: Boolean = false,
    accept: (String) -> Boolean,
): ImportArchive {
    val names = mutableListOf<String>()
    val entries = mutableMapOf<String, ByteArray>()
    walkArchive(password, allowAge = allowAge, maxEntryBytes = IMPORT_TEXT_MAX_BYTES) { entry ->
        names += entry.name
        if (accept(entry.name)) {
            entries[entry.name] = entry.source.readByteArray()
        }
    }
    return ImportArchive(
        names = names,
        entries = entries,
    )
}

/**
 * Reads an archive for a format that does not support ZIP encryption.
 * A password cannot resolve an encrypted entry, including an ignored one.
 */
internal fun ImportInput.readUnencryptedArchive(
    accept: (String) -> Boolean,
): ImportArchive = try {
    readArchive(password = null, accept = accept)
} catch (_: ImportArchivePasswordException) {
    throw ImportArchiveUnsupportedEncryptionException()
}

internal fun ImportInput.readUnencryptedArchiveEntries(
    accept: (String) -> Boolean,
): Map<String, ByteArray> = readUnencryptedArchive(accept).entries

/**
 * Streams the entries named in [refs] into [sink] in a single walk over the
 * archive, answering what the sink returned per entry name. Throws if the
 * archive no longer holds one of them.
 */
internal suspend fun <T> ImportInput.extractArchiveEntries(
    password: Password?,
    refs: Set<String>,
    sink: ImportAttachmentSink<T>,
    allowAge: Boolean = false,
): Map<String, T> {
    val files = mutableMapOf<String, T>()
    walkArchive(password, allowAge = allowAge) { entry ->
        // A second entry of the same name would be a second read of a ref
        // the sink has already consumed.
        if (entry.name in refs && entry.name !in files) {
            files[entry.name] = sink.write(
                ref = entry.name,
                source = entry.source,
            )
        }
    }
    // The file may have changed since the review; the commit must fail
    // before it writes anything rather than halfway through.
    check(files.size == refs.size) {
        "${refs.size - files.size} attachments are missing from the archive."
    }
    return files
}

/**
 * Walks the entries of an archive, refusing more than [MAX_ARCHIVE_ENTRIES]
 * of them and an entry that yields more than [maxEntryBytes].
 */
private inline fun ImportInput.walkArchive(
    password: Password?,
    allowAge: Boolean = false,
    maxEntryBytes: Long = Long.MAX_VALUE,
    block: (ZipReaderEntry) -> Unit,
) {
    try {
        open().use { source ->
            val reader = if (allowAge && source.isAgeArchive()) {
                readAgeArchive(source, password?.value?.let(::AgePassword), maxEntryBytes = maxEntryBytes)
            } else {
                ZipReader(source, password?.value, MAX_ARCHIVE_ENTRIES, maxEntryBytes)
            }
            reader.use { reader ->
                while (true) {
                    val entry = reader.nextEntry()
                        ?: break
                    block(entry)
                }
            }
        }
    } catch (_: ZipPasswordException) {
        throw ImportArchivePasswordException()
    } catch (e: ZipArchiveException) {
        throw when (e.reason) {
            ZipArchiveError.TooLarge,
            ZipArchiveError.TooManyEntries,
            -> ImportTooLargeException()

            else -> e
        }
    }
}
