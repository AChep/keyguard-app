package com.artemchep.keyguard.common.service.imports.impl

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportResult
import kotlin.time.Instant

/**
 * Reads one file format into an import plan. Implementations stay lenient per
 * record — a record that cannot be represented is a counted skip — and throw
 * only for a file that is not of their format at all.
 */
internal interface Importer {
    val format: ImportFormat

    fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult
}

/**
 * An [Importer] of an archive whose plan carries attachments.
 */
internal interface ImportAttachmentExtractor {
    suspend fun <T> extract(
        input: ImportInput,
        password: Password?,
        refs: Set<String>,
        sink: ImportAttachmentSink<T>,
    ): Map<String, T>
}
