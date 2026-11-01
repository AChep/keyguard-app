package com.artemchep.keyguard.common.service.imports.impl

import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.util.io.SourceTooLargeException
import com.artemchep.keyguard.util.io.decodeTextDetectingEncoding
import com.artemchep.keyguard.util.io.limitedTo
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * The largest file an importer reads into memory. Generous for a text export —
 * tens of thousands of items fit in a few megabytes — while keeping a hostile
 * file from exhausting the heap of a phone.
 */
internal const val IMPORT_TEXT_MAX_BYTES = 64L * 1024L * 1024L

/**
 * The file exceeds [IMPORT_TEXT_MAX_BYTES]. Reported to the user as such
 * rather than as a parse failure, because the file may well be valid.
 */
internal class ImportTooLargeException : RuntimeException()

/**
 * Runs [block], reporting a source that exceeded its limit as
 * [ImportTooLargeException].
 */
internal inline fun <T> mapImportTooLarge(
    block: () -> T,
): T = try {
    block()
} catch (_: SourceTooLargeException) {
    throw ImportTooLargeException()
}

internal fun ImportInput.readBytes(
    maxBytes: Long = IMPORT_TEXT_MAX_BYTES,
): ByteArray = open().use { source ->
    source.readBytesCapped(maxBytes)
}

internal fun Source.readBytesCapped(
    maxBytes: Long,
): ByteArray = mapImportTooLarge {
    limitedTo(maxBytes).readByteArray()
}

/**
 * Reads an exported text file. Browsers and password managers write UTF-8,
 * but a file that passed through a spreadsheet application may come back as
 * UTF-16 with a byte-order mark, or as Windows-1252 without one.
 */
internal fun ImportInput.readText(
    maxBytes: Long = IMPORT_TEXT_MAX_BYTES,
): String = decodeTextDetectingEncoding(readBytes(maxBytes))
