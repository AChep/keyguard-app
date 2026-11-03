package com.artemchep.keyguard.common.service.imports.impl.onepassword

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV
import com.artemchep.keyguard.common.service.imports.impl.ImportAttachmentExtractor
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.isZipArchive
import kotlin.time.Instant

private const val IMPORT_EXTENSION_1PUX = "1pux"

/**
 * 1Password, in either of its exports: the 1PUX archive, told apart by its
 * ZIP signature, or the CSV.
 */
internal class OnePasswordImporter(
    sshKeyImportService: SshKeyImportService,
) : Importer, ImportAttachmentExtractor {
    override val format = ImportFormat(
        id = ImportFormatId.OnePassword,
        title = "1Password",
        extensions = listOf(IMPORT_EXTENSION_1PUX, IMPORT_EXTENSION_CSV),
    )

    private val pux = OnePasswordPuxImporter(
        format = format,
        sshKeyImportService = sshKeyImportService,
    )

    private val csv = OnePasswordCsvImporter(
        format = format,
    )

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult = if (input.isZipArchive()) {
        pux.parse(input, password, now)
    } else {
        csv.parse(input, password, now)
    }

    // Only a 1PUX plan has attachments.
    override suspend fun <T> extract(
        input: ImportInput,
        password: Password?,
        refs: Set<String>,
        sink: ImportAttachmentSink<T>,
    ): Map<String, T> = pux.extract(input, password, refs, sink)
}
