package com.artemchep.keyguard.common.service.imports.impl

import com.artemchep.keyguard.common.io.runCatchingUntrustedInput
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
import com.artemchep.keyguard.common.service.imports.ImportService
import com.artemchep.keyguard.common.service.imports.ImportSourceSnapshot
import com.artemchep.keyguard.common.service.imports.OpenedImportSource
import com.artemchep.keyguard.common.service.imports.impl.bitwarden.BitwardenImporter
import com.artemchep.keyguard.common.service.imports.impl.chromium.ChromiumCsvImporter
import com.artemchep.keyguard.common.service.imports.impl.dashlane.DashlaneImporter
import com.artemchep.keyguard.common.service.imports.impl.enpass.EnpassJsonImporter
import com.artemchep.keyguard.common.service.imports.impl.firefox.FirefoxCsvImporter
import com.artemchep.keyguard.common.service.imports.impl.keepassxc.KeePassXcCsvImporter
import com.artemchep.keyguard.common.service.imports.impl.keeper.KeeperCsvImporter
import com.artemchep.keyguard.common.service.imports.impl.keyguard.KeyguardZipImporter
import com.artemchep.keyguard.common.service.imports.impl.lastpass.LastPassCsvImporter
import com.artemchep.keyguard.common.service.imports.impl.nordpass.NordPassCsvImporter
import com.artemchep.keyguard.common.service.imports.impl.onepassword.OnePasswordImporter
import com.artemchep.keyguard.common.service.imports.impl.protonpass.ProtonPassImporter
import com.artemchep.keyguard.common.service.imports.impl.safari.SafariCsvImporter
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/**
 * The [ImportService] of every format Keyguard can import.
 */
class ImportServiceImpl(
    cryptoGenerator: CryptoGenerator,
    cipherEncryptor: CipherEncryptor,
    sshKeyImportService: SshKeyImportService,
) : ImportService by ImporterImportService(
    importers = defaultImporters(
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = cipherEncryptor,
        sshKeyImportService = sshKeyImportService,
    ),
)

/**
 * The import boundary, dispatching to one [Importer] per format.
 *
 * The [runCatchingUntrustedInput] guard in this file wraps every importer's
 * walk over a file handed over by another application. That is why the detekt
 * allow-list for that helper names this whole file: the file is the boundary.
 */
internal class ImporterImportService(
    private val importers: List<Importer>,
) : ImportService {
    init {
        val duplicates = importers
            .groupBy { it.format.id }
            .filterValues { it.size > 1 }
            .keys
        require(duplicates.isEmpty()) {
            "Several importers read the same format: $duplicates"
        }
    }

    override val formats: List<ImportFormat> = importers
        .map { it.format }
        .sortedBy { it.id.ordinal }

    override fun open(
        format: ImportFormatId,
        input: ImportInput,
        password: Password?,
    ): OpenedImportSource = FileSource(
        importer = importers.first { it.format.id == format },
        input = input,
        password = password,
    )

    private class FileSource(
        private val importer: Importer,
        private val input: ImportInput,
        private val password: Password?,
    ) : OpenedImportSource {
        override val snapshots: List<ImportSourceSnapshot> get() = emptyList()

        override suspend fun parse(
            snapshotId: String?,
            now: Instant,
        ): ImportResult {
            // IO rather than Default: an importer reads the file as it parses,
            // and a document provider may block on the network for a while.
            val result = withContext(Dispatchers.IO) {
                // Only a file that is not of the chosen format fails the import;
                // anything unexpected — including a blown heap, which at this
                // boundary is a property of the file — becomes exactly that.
                runCatchingUntrustedInput { importer.parse(input, password, now) }
                    .getOrElse { e ->
                        val error = when (e) {
                            is ImportTooLargeException -> ImportError.TooLarge
                            is ImportArchiveUnsupportedEncryptionException -> ImportError.UnsupportedEncryption
                            is ImportArchivePasswordException -> if (password == null) {
                                ImportError.PasswordRequired
                            } else {
                                ImportError.WrongPassword
                            }
                            is IOException -> ImportError.Unreadable
                            else -> ImportError.Parse
                        }
                        ImportResult.Failure(error)
                    }
            }
            // Outside the guard on purpose: this is a bug of the importer, not a
            // property of the file, and it must fail here rather than at the
            // commit, after the user has reviewed the plan.
            check(
                importer is ImportAttachmentExtractor ||
                    result !is ImportResult.Success ||
                    result.plan.items.none { it.attachments.isNotEmpty() },
            ) {
                "The ${importer.format.id} importer planned attachments it cannot extract."
            }
            return result
        }

        override suspend fun <T> extract(
            refs: Set<String>,
            sink: ImportAttachmentSink<T>,
        ): Map<String, T> {
            if (refs.isEmpty()) {
                return emptyMap()
            }
            val extractor = importer as? ImportAttachmentExtractor
            checkNotNull(extractor) {
                "The ${importer.format.id} importer has no attachments to extract."
            }
            return withContext(Dispatchers.IO) {
                extractor.extract(input, password, refs, sink)
            }
        }

        // The file belongs to whoever picked it.
        override suspend fun close() = Unit
    }
}

private fun defaultImporters(
    cryptoGenerator: CryptoGenerator,
    cipherEncryptor: CipherEncryptor,
    sshKeyImportService: SshKeyImportService,
): List<Importer> = listOf(
    ChromiumCsvImporter(),
    FirefoxCsvImporter(),
    SafariCsvImporter(),
    BitwardenImporter(
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = cipherEncryptor,
    ),
    KeePassXcCsvImporter(),
    OnePasswordImporter(
        sshKeyImportService = sshKeyImportService,
    ),
    DashlaneImporter(),
    EnpassJsonImporter(),
    KeeperCsvImporter(
        sshKeyImportService = sshKeyImportService,
    ),
    LastPassCsvImporter(
        sshKeyImportService = sshKeyImportService,
    ),
    NordPassCsvImporter(),
    ProtonPassImporter(
        sshKeyImportService = sshKeyImportService,
    ),
    KeyguardZipImporter(
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = cipherEncryptor,
    ),
)
