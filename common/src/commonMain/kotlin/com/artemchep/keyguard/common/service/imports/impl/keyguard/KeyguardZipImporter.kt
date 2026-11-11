package com.artemchep.keyguard.common.service.imports.impl.keyguard

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.impl.ImportAttachmentExtractor
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.bitwarden.BitwardenJsonImporter
import com.artemchep.keyguard.common.service.imports.impl.extractArchiveEntries
import com.artemchep.keyguard.common.service.imports.impl.readArchive
import com.artemchep.keyguard.crypto.PrivateTemporaryDirProvider
import com.artemchep.keyguard.util.io.decodeTextDetectingEncoding
import kotlin.time.Instant

private const val IMPORT_EXTENSION_ZIP = "zip"

/**
 * Keyguard as an import source. Its automatic backups hold the same
 * `vault.json`, so they share it.
 */
internal val keyguardImportFormat = ImportFormat(
    id = ImportFormatId.KeyguardZip,
    title = "Keyguard",
    extensions = listOf(IMPORT_EXTENSION_ZIP, "zip.age"),
)

/**
 * Keyguard's own export: a password-protected archive of `vault.json`, the
 * extended Bitwarden JSON, and the attached files as
 * `attachments/<attachment id>/<file name>`.
 *
 * Parsing only lists the attachments; their bytes are read when the import
 * is committed, and only for the selected items. An age-encrypted backup
 * spools its ciphertext into the directory of [temporaryDirProvider].
 */
internal class KeyguardZipImporter(
    cryptoGenerator: CryptoGenerator,
    cipherEncryptor: CipherEncryptor,
    sshKeyImportService: SshKeyImportService,
    private val temporaryDirProvider: PrivateTemporaryDirProvider,
) : Importer, ImportAttachmentExtractor {
    companion object {
        private const val VAULT_ENTRY = "vault.json"
        private const val ATTACHMENTS_PREFIX = "attachments/"
    }

    override val format = keyguardImportFormat

    private val json = BitwardenJsonImporter(
        format = format,
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = cipherEncryptor,
        sshKeyImportService = sshKeyImportService,
    )

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult {
        val archive = input.readArchive(
            password = password,
            ageTemporaryDirProvider = temporaryDirProvider,
        ) { it == VAULT_ENTRY }
        val vault = archive.entries[VAULT_ENTRY]
            ?: return ImportResult.Failure(ImportError.Parse)
        val attachmentEntries = archive.namesByKey(
            prefix = ATTACHMENTS_PREFIX,
            delimiter = '/',
        )
        return json.parseText(
            text = decodeTextDetectingEncoding(vault),
            password = null,
            now = now,
            attachments = { _, attachmentId ->
                attachmentEntries[attachmentId]
            },
        )
    }

    override suspend fun <T> extract(
        input: ImportInput,
        password: Password?,
        refs: Set<String>,
        sink: ImportAttachmentSink<T>,
    ): Map<String, T> = input.extractArchiveEntries(
        password = password,
        refs = refs,
        sink = sink,
        ageTemporaryDirProvider = temporaryDirProvider,
    )
}
