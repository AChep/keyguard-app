package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.util.age.AgeIdentity
import com.artemchep.keyguard.util.zip.ZipArchiveError
import com.artemchep.keyguard.util.zip.ZipArchiveException
import com.artemchep.keyguard.util.zip.ZipService
import kotlinx.io.readString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val BACKUP_AGE_VERSION = 2
internal const val BACKUP_AGE_CRYPTO = "zip-age-scrypt"
internal const val BACKUP_AGE_INDEX_CRYPTO = "zip-age-x25519"
internal const val BACKUP_AGE_INDEX_FEATURE = "age-index-key"
internal const val BACKUP_AES_CRYPTO = "zip-aes-256"
internal const val BACKUP_AGE_MIGRATION_FILE = "encryption-upgrade.zip.age"
internal const val BACKUP_REPO_FILE = "repo.zip"
internal const val BACKUP_AGE_REPO_FILE = "repo.zip.age"
internal const val BACKUP_REPO_ENTRY = "repo.json"
internal const val AGE_SUFFIX = ".age"
private const val JOURNAL_VERSION = 3
private const val JOURNAL_ENTRY = "upgrade.json"
private const val MAX_METADATA_BYTES = 64L * 1024L * 1024L

/** A new random identity for [BackupRepositoryKeys.index]. */
internal fun newBackupIndexKey() = BackupSecretKey(AgeIdentity.generate().value)

/**
 * Metadata is encrypted with the password; indexes with [indexKey], which the
 * metadata holds, so a run derives a key from the password only once.
 */
internal fun BackupRepositoryMetadata.withAgeEncryption(indexKey: BackupSecretKey) = copy(
    formatVersion = BACKUP_AGE_VERSION,
    features = features.filterNot { it == "encrypted-zip-metadata" } +
        "age-encrypted-metadata" + BACKUP_AGE_INDEX_FEATURE,
    crypto = crypto.copy(archive = BACKUP_AGE_CRYPTO, indexArchive = BACKUP_AGE_INDEX_CRYPTO),
    layout = layout.copy(repo = BACKUP_AGE_REPO_FILE),
    keys = BackupRepositoryKeys(index = indexKey),
)

/** The identity `.age` indexes are encrypted to, if this is an age repository. */
internal val BackupRepositoryMetadata.indexIdentity: AgeIdentity?
    get() = keys?.index?.let { AgeIdentity(it.value) }

internal val BackupRepositoryMetadata.isAge: Boolean get() = formatVersion == BACKUP_AGE_VERSION

private val BackupRepositoryMetadata.isUpgradeable: Boolean
    get() = formatVersion == 1 && crypto.archive == BACKUP_AES_CRYPTO

internal val BackupRepositoryMetadata.encryption: BackupRepositoryEncryption
    get() = when {
        isAge -> BackupRepositoryEncryption.Age
        isUpgradeable -> BackupRepositoryEncryption.ZipAes
        else -> BackupRepositoryEncryption.None
    }

/** No keys or password verifier; the old reader rejects its features/layout. */
internal fun BackupRepositoryMetadata.compatibilityMarker() = copy(
    features = listOf("age-repository-marker"),
    crypto = backupRepositoryCrypto(null),
    // The marker is stored unencrypted.
    keys = null,
)

@Serializable
private data class AgeUpgradeJournal(
    val version: Int = JOURNAL_VERSION,
    val repositoryJson: String,
    /** Fixed before publication, so a resumed upgrade publishes the same metadata. */
    val indexKey: BackupSecretKey,
)

/**
 * Replace repository metadata after verification; leave all indexes untouched.
 * The authenticated journal survives publication and cleanup failures.
 * Callers must stop other repository writers;
 * neither local mutexes nor the object-store API provide a distributed lock.
 */
internal class BackupAgeMigration(
    private val json: Json,
    private val zipService: ZipService,
    /** Replaces `repo.zip` with the verified marker of the given age metadata. */
    private val replaceLegacyWithMarker: suspend (BackupObjectStore, BackupRepositoryMetadata) -> Unit,
) {
    suspend fun run(store: BackupObjectStore, password: Password) {
        require(password.value.isNotEmpty()) { "Encrypted backup requires a password." }
        val journal = openJournal(store, password) ?: return
        require(journal.version == JOURNAL_VERSION) { "Unsupported backup upgrade journal version." }
        val oldMetadata = json.decodeFromString<BackupRepositoryMetadata>(journal.repositoryJson)
        validateBackupRepositoryFormat(oldMetadata)
        require(oldMetadata.isUpgradeable)
        val metadata = oldMetadata.withAgeEncryption(journal.indexKey)
        validateBackupRepositoryFormat(metadata)
        val published = readAgeOrNull(store, BACKUP_AGE_REPO_FILE, password, BACKUP_REPO_ENTRY)
            ?.let { json.decodeFromString<BackupRepositoryMetadata>(it) }
        check(published == null || published == metadata) { "The upgraded repository metadata changed." }
        val committed = published != null

        if (!committed) {
            check(read(store, BACKUP_REPO_FILE, password, BACKUP_REPO_ENTRY) == journal.repositoryJson) {
                "The backup metadata changed during the upgrade."
            }
            write(store, BACKUP_AGE_REPO_FILE, password, BACKUP_REPO_ENTRY, json.encodeToString(metadata))
        }
        check(readAgeMetadata(store, password) == metadata)

        // Replace only the old repository metadata, after the new metadata has
        // been read back and fully authenticated. Legacy indexes stay readable.
        finishCommitted(store, metadata, journalExists = true)
    }

    /** Publishes the marker for committed age [metadata], then removes the journal. */
    private suspend fun finishCommitted(
        store: BackupObjectStore,
        metadata: BackupRepositoryMetadata,
        journalExists: Boolean,
    ) {
        replaceLegacyWithMarker(store, metadata)
        if (journalExists) {
            store.delete(BackupObjectKey(BACKUP_AGE_MIGRATION_FILE))
            check(!exists(store, BACKUP_AGE_MIGRATION_FILE)) { "Backup upgrade cleanup is incomplete." }
        }
    }

    private suspend fun openJournal(store: BackupObjectStore, password: Password): AgeUpgradeJournal? {
        val journalExists = exists(store, BACKUP_AGE_MIGRATION_FILE)
        if (journalExists) {
            try {
                return json.decodeFromString(read(store, BACKUP_AGE_MIGRATION_FILE, password, JOURNAL_ENTRY))
            } catch (e: ZipArchiveException) {
                // An interrupted first publication may leave a partial journal:
                // rebuild it after authenticating the original. After commit,
                // the authenticated age metadata replaces a damaged journal.
                if (e.reason != ZipArchiveError.Invalid) throw e
            }
        }
        return prepareUpgrade(store, password, journalExists)
    }

    private suspend fun prepareUpgrade(
        store: BackupObjectStore,
        password: Password,
        journalExists: Boolean,
    ): AgeUpgradeJournal? {
        if (exists(store, BACKUP_AGE_REPO_FILE)) {
            // Authenticating it with the password proves the upgrade
            // committed, and it holds everything the journal did.
            val metadata = readAgeMetadata(store, password)
            validateBackupRepositoryFormat(metadata)
            require(metadata.isAge)
            finishCommitted(store, metadata, journalExists)
            return null
        }
        val original = read(store, BACKUP_REPO_FILE, password, BACKUP_REPO_ENTRY)
        val metadata = json.decodeFromString<BackupRepositoryMetadata>(original)
        validateBackupRepositoryFormat(metadata)
        require(metadata.isUpgradeable) {
            "Only password-protected backups can be upgraded."
        }
        val journal = AgeUpgradeJournal(repositoryJson = original, indexKey = newBackupIndexKey())
        write(
            store, BACKUP_AGE_MIGRATION_FILE, password, JOURNAL_ENTRY, json.encodeToString(journal),
            mode = if (journalExists) BackupWriteMode.CreateOrReplace else BackupWriteMode.Create,
        )
        val published = read(store, BACKUP_AGE_MIGRATION_FILE, password, JOURNAL_ENTRY)
        check(json.decodeFromString<AgeUpgradeJournal>(published) == journal)
        return journal
    }

    private suspend fun readAgeMetadata(store: BackupObjectStore, password: Password) =
        json.decodeFromString<BackupRepositoryMetadata>(read(store, BACKUP_AGE_REPO_FILE, password, BACKUP_REPO_ENTRY))

    private suspend fun readAgeOrNull(
        store: BackupObjectStore,
        path: String,
        password: Password,
        entry: String,
    ): String? {
        if (!exists(store, path)) return null
        return try {
            read(store, path, password, entry)
        } catch (e: ZipArchiveException) {
            e.throwIfFatalOrCancellation()
            if (e.reason != ZipArchiveError.Invalid) throw e
            // A failed publication can leave an incomplete destination on a
            // provider without atomic writes. Its authenticated original still
            // exists; the journal limits recovery to this migration's objects.
            null
        }
    }

    private suspend fun read(store: BackupObjectStore, path: String, password: Password?, entry: String): String {
        val key = BackupObjectKey(path)
        return openBackupArchive(key, store.read(key), password, maxEntryBytes = MAX_METADATA_BYTES).use {
            val member = requireNotNull(it.nextEntry()) { "Backup metadata is missing." }
            require(member.name == entry) { "Unexpected backup metadata entry." }
            val text = member.source.readString()
            require(it.nextEntry() == null) { "Unexpected additional backup metadata entry." }
            text
        }
    }

    private suspend fun write(
        store: BackupObjectStore,
        path: String,
        password: Password?,
        entry: String,
        text: String,
        mode: BackupWriteMode = BackupWriteMode.CreateOrReplace,
    ) {
        store.writeBackupArchive(zipService, BackupObjectKey(path), mode, password, listOf(textEntry(entry, text)))
    }

    private suspend fun exists(store: BackupObjectStore, path: String) = store.stat(BackupObjectKey(path)) != null
}

/** A started encryption upgrade must be resumed before the repository is written. */
class BackupEncryptionUpgradePendingException : IllegalStateException(
    "Backup encryption upgrade must be resumed before writing.",
)

internal suspend fun requireNoPendingEncryptionUpgrade(store: BackupObjectStore) {
    if (store.stat(BackupObjectKey(BACKUP_AGE_MIGRATION_FILE)) != null) {
        throw BackupEncryptionUpgradePendingException()
    }
}
