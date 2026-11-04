package com.artemchep.keyguard.common.service.imports.impl.keyguardbackup

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.runCatchingUntrustedInput
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.backup.BackupIndex
import com.artemchep.keyguard.common.service.backup.BackupObjectStore
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreException
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupRepository
import com.artemchep.keyguard.common.service.backup.BackupSnapshotManifest
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.mergeBackupIndexes
import com.artemchep.keyguard.common.service.backup.toPassword
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.imports.BackupImportError
import com.artemchep.keyguard.common.service.imports.BackupImportService
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportOpenError
import com.artemchep.keyguard.common.service.imports.ImportOpenResult
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportSourceSnapshot
import com.artemchep.keyguard.common.service.imports.OpenedImportSource
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_TEXT_MAX_BYTES
import com.artemchep.keyguard.common.service.imports.impl.ImportTooLargeException
import com.artemchep.keyguard.common.service.imports.impl.bitwarden.BitwardenJsonAttachments
import com.artemchep.keyguard.common.service.imports.impl.bitwarden.BitwardenJsonImporter
import com.artemchep.keyguard.common.service.imports.impl.keyguard.keyguardImportFormat
import com.artemchep.keyguard.common.service.imports.impl.readBytesCapped
import com.artemchep.keyguard.util.io.decodeTextDetectingEncoding
import com.artemchep.keyguard.util.zip.ZipPasswordException
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/**
 * The backup import boundary.
 *
 * The [runCatchingUntrustedInput] guard in this file wraps the walk over a
 * snapshot's `vault.json`, which is why the detekt allow-list for that
 * helper names this file.
 */
class BackupImportServiceImpl(
    private val storeFactory: BackupObjectStoreFactory,
    private val backupRepository: BackupRepository,
    cryptoGenerator: CryptoGenerator,
    cipherEncryptor: CipherEncryptor,
) : BackupImportService {
    private val json = BitwardenJsonImporter(
        format = keyguardImportFormat,
        cryptoGenerator = cryptoGenerator,
        cipherEncryptor = cipherEncryptor,
    )

    // Every call reads the backup location — a folder, or a server — so it
    // runs on IO rather than Default.
    override suspend fun open(
        store: BackupStoreConfig,
        password: Password?,
    ): ImportOpenResult {
        // Owned here until a success hands it to the caller. A cancelled open
        // closes it too, including one whose result is lost as it returns.
        var openedStore: BackupObjectStore? = null
        try {
            val result = withContext(Dispatchers.IO) {
                val objectStore = runCatchingNonFatal { storeFactory.open(store) }
                    .getOrElse { e ->
                        return@withContext ImportOpenResult.Failure(e.toOpenError(password))
                    }
                openedStore = objectStore
                runCatchingNonFatal { openRepository(objectStore, password) }
                    .getOrElse { e -> ImportOpenResult.Failure(e.toOpenError(password)) }
            }
            if (result is ImportOpenResult.Success) {
                openedStore = null
            }
            return result
        } finally {
            openedStore?.let { objectStore ->
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatchingNonFatal { objectStore.close() }
                }
            }
        }
    }

    private suspend fun openRepository(
        store: BackupObjectStore,
        password: Password?,
    ): ImportOpenResult {
        // Read first: it fails on a wrong password, while reading the indexes
        // without one would answer an empty repository.
        val metadata = backupRepository.readMetadata(store, password)
            ?: return ImportOpenResult.Failure(BackupImportError.NotFound)
        val indexes = backupRepository.readIndexes(store, metadata, password)
        val index = mergeBackupIndexes(indexes).index
        val snapshots = index.snapshots
            .map { (id, snapshot) ->
                ImportSourceSnapshot(
                    id = id,
                    createdAt = snapshot.createdAt,
                    itemCount = snapshot.stats.cipherCount,
                    attachmentCount = snapshot.stats.attachmentCount,
                )
            }
            .sortedWith(
                compareByDescending<ImportSourceSnapshot> { it.createdAt }
                    .thenByDescending { it.id },
            )
        return if (snapshots.isEmpty()) {
            ImportOpenResult.Failure(BackupImportError.Empty)
        } else {
            val repository = Repository(
                store = store,
                index = index,
                snapshots = snapshots,
            )
            ImportOpenResult.Success(repository)
        }
    }

    private inner class Repository(
        private val store: BackupObjectStore,
        private val index: BackupIndex,
        override val snapshots: List<ImportSourceSnapshot>,
    ) : OpenedImportSource {
        override suspend fun parse(
            snapshotId: String?,
            now: Instant,
        ): ImportResult = withContext(Dispatchers.IO) {
            val snapshot = snapshotId?.let(index.snapshots::get)
                ?: return@withContext ImportResult.Failure(ImportError.Parse)
            // A snapshot whose `vault.json` is not an export fails the import;
            // anything unexpected — a blown heap included — becomes exactly that.
            runCatchingUntrustedInput {
                backupRepository.readSnapshot(
                    store = store,
                    objectPassword = snapshot.encryption.toPassword(),
                    snapshotPath = snapshot.path,
                ) { manifest, vault ->
                    val text = decodeTextDetectingEncoding(vault.readBytesCapped(IMPORT_TEXT_MAX_BYTES))
                    json.parseText(
                        text = text,
                        password = null,
                        now = now,
                        attachments = manifest.attachmentRefs(index),
                    )
                } ?: ImportResult.Failure(ImportError.Parse)
            }.getOrElse { e ->
                val error = when (e) {
                    is ImportTooLargeException -> ImportError.TooLarge
                    is BackupObjectStoreException,
                    is IOException,
                    -> ImportError.Unreadable

                    else -> ImportError.Parse
                }
                ImportResult.Failure(error)
            }
        }

        override suspend fun <T> extract(
            refs: Set<String>,
            sink: ImportAttachmentSink<T>,
        ): Map<String, T> = withContext(Dispatchers.IO) {
            // A blob is a round trip to the location; reading a few at a time
            // keeps a remote backup with hundreds of them from taking minutes.
            val permits = Semaphore(ATTACHMENT_READ_CONCURRENCY)
            refs
                .map { ref ->
                    async {
                        permits.withPermit {
                            ref to extractAttachment(ref, sink)
                        }
                    }
                }
                .awaitAll()
                .toMap()
        }

        private suspend fun <T> extractAttachment(
            ref: String,
            sink: ImportAttachmentSink<T>,
        ): T {
            val blobId = ref.substringBefore(REF_DELIMITER)
            val blob = requireNotNull(index.blobs[blobId]) {
                "Backup blob '$blobId' is not in the index."
            }
            return backupRepository.readBlob(
                store = store,
                objectPassword = blob.encryption.toPassword(),
                blobPath = blob.path,
            ) { source ->
                sink.write(ref, source)
            } ?: error("Backup blob '$blobId' is missing.")
        }

        // Closing may still talk to the location, so it runs to its end.
        override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
            store.close()
        }
    }
}

/**
 * An attachment ref is `<blob id>/<file name>`: the blob holds the bytes,
 * and the name stands in for one `vault.json` left blank.
 */
private const val REF_DELIMITER = '/'

/**
 * How many blobs a commit reads at once.
 */
private const val ATTACHMENT_READ_CONCURRENCY = 4

/**
 * The attachments this snapshot stored, keyed the way `vault.json` lists
 * them: by the item, whose id is its remote id if it has one, and the
 * attachment. One whose blob is not in the [index] is left out, and so
 * counted as skipped.
 */
private fun BackupSnapshotManifest.attachmentRefs(
    index: BackupIndex,
): BitwardenJsonAttachments {
    val byAttachmentId = attachments
        .filter { it.blobId in index.blobs }
        .groupBy { it.attachmentId }
    return BitwardenJsonAttachments { itemId, attachmentId ->
        byAttachmentId[attachmentId]
            ?.firstOrNull { itemId == it.remoteCipherId || itemId == it.localCipherId }
            ?.let { "${it.blobId}$REF_DELIMITER${it.fileName}" }
    }
}

private fun Throwable.toOpenError(
    password: Password?,
): ImportOpenError = when (this) {
    is ZipPasswordException -> if (password == null) {
        ImportError.PasswordRequired
    } else {
        ImportError.WrongPassword
    }

    is BackupObjectStoreException.AuthenticationFailed,
    is BackupObjectStoreException.PermissionDenied,
    -> BackupImportError.AccessDenied

    is BackupObjectStoreException.NotFound -> BackupImportError.NotFound

    is BackupObjectStoreException.Transient,
    is IOException,
    -> BackupImportError.Unreachable

    else -> ImportError.Unreadable
}
