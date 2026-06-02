package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.withBufferedSink
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.Password
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlin.time.Instant
import kotlinx.io.Sink
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.serialization.json.Json
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.kodein.di.DirectDI
import org.kodein.di.instance

class BackupRepositoryZipImpl(
    private val json: Json,
) : BackupRepository {
    companion object {
        private const val REPO_FILE = "repo.zip"
        private const val INDEXES_DIR = "indexes"
        private const val SNAPSHOTS_DIR = "snapshots"

        private const val REPO_ENTRY = "repo.json"
        private const val INDEX_ENTRY = "index.json"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val VAULT_ENTRY = "vault.json"
        private const val BLOB_ENTRY = "attachment.bin"

        private val INDEX_GENERATION_FILE_REGEX = Regex("""(\d{20})-(.+)\.zip""")
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        json = directDI.instance(),
    )

    override suspend fun getOrCreateMetadata(
        store: BackupObjectStore,
        password: Password?,
        nowProvider: () -> Instant,
        repoIdProvider: () -> String,
    ): BackupRepositoryMetadata {
        val existing = readZipJsonOrNull<BackupRepositoryMetadata>(
            store = store,
            key = objectKey(REPO_FILE),
            password = password,
            entryName = REPO_ENTRY,
        )
        if (existing != null) {
            validateBackupRepositoryMetadata(
                metadata = existing,
                password = password,
            )
            return existing
        }

        val now = nowProvider()
        val repoId = repoIdProvider()
        val metadata = BackupRepositoryMetadata(
            repoId = repoId,
            createdAt = now,
            features = backupRepositoryFeatures(password),
            crypto = backupRepositoryCrypto(password),
        )
        try {
            writeZip(
                store = store,
                key = objectKey(REPO_FILE),
                mode = BackupWriteMode.Create,
                password = password,
                entries = listOf(
                    ZipEntryWriter.Text(
                        name = REPO_ENTRY,
                        text = json.encodeToString(metadata),
                    ),
                ),
            )
        } catch (e: BackupObjectStoreException.AlreadyExists) {
            val raced = readZipJsonOrNull<BackupRepositoryMetadata>(
                store = store,
                key = objectKey(REPO_FILE),
                password = password,
                entryName = REPO_ENTRY,
            ) ?: throw e
            validateBackupRepositoryMetadata(
                metadata = raced,
                password = password,
            )
            return raced
        }
        return metadata
    }

    override suspend fun readIndexes(
        store: BackupObjectStore,
        password: Password?,
    ): List<BackupIndex> {
        val keysByGeneration = listIndexGenerationKeys(store)
            .groupBy { it.generation }
        /*
         * This is intentionally an authoritative newest-generation read, not a
         * full cross-generation DAG scan. Retention may publish a second, newer
         * index generation after a backup index to record pruned snapshots and
         * blobs. Once any readable index exists in that newer generation, older
         * generations stay ignored so stale heads cannot resurrect data that
         * retention already dropped.
         *
         * Accepted risks:
         * - A concurrent backup that started from an older head can later publish
         *   a lower-generation index. It will be permanently ignored instead of
         *   merged.
         * - Multi-writer conflict resolution is best-effort; generation order is
         *   the authority.
         */
        keysByGeneration.values.forEach { generationKeys ->
            val indexes = generationKeys
                .mapNotNull { generationKey ->
                    readIndexAtKey(
                        store = store,
                        password = password,
                        key = generationKey.key,
                    )?.takeIf { index ->
                        index.generation == generationKey.generation && index.indexId == generationKey.indexId
                    }
                }
            if (indexes.isNotEmpty()) {
                return indexes
            }
        }
        return emptyList()
    }

    override suspend fun writeIndex(
        store: BackupObjectStore,
        password: Password?,
        index: BackupIndex,
    ) {
        require(index.generation >= 0L) {
            "Backup index generation must not be negative."
        }
        require(index.indexId.isNotBlank()) {
            "Backup index id must not be blank."
        }
        val key = indexGenerationKey(index)
        writeZip(
            store = store,
            key = key,
            mode = BackupWriteMode.Create,
            password = password,
            entries = listOf(
                ZipEntryWriter.Text(
                    name = INDEX_ENTRY,
                    text = json.encodeToString(index),
                ),
            ),
        )
        require(readIndexAtKey(store, password, key) == index) {
            "Failed to verify backup index '${key.value}' after writing."
        }
    }

    override suspend fun hasBlob(
        store: BackupObjectStore,
        blobPath: String,
    ): Boolean = store.stat(objectKey(blobPath)) != null

    override suspend fun validateBlob(
        store: BackupObjectStore,
        objectPassword: Password?,
        blobPath: String,
    ): BackupBlobValidationResult = try {
        val key = objectKey(blobPath)
        if (store.stat(key) != null) {
            val readable = zipContainsReadableEntry(
                store = store,
                key = key,
                password = objectPassword,
                entryName = BLOB_ENTRY,
            )
            if (readable) {
                BackupBlobValidationResult.Valid
            } else {
                BackupBlobValidationResult.Invalid
            }
        } else {
            BackupBlobValidationResult.Invalid
        }
    } catch (_: BackupObjectStoreException.NotFound) {
        BackupBlobValidationResult.Invalid
    } catch (_: BackupObjectStoreException.Transient) {
        BackupBlobValidationResult.Unavailable
    } catch (e: BackupObjectStoreException) {
        throw e
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        BackupBlobValidationResult.Invalid
    }

    override suspend fun writeBlob(
        store: BackupObjectStore,
        objectPassword: Password?,
        blobPath: String,
        write: suspend (Sink) -> Unit,
    ): Long {
        val key = objectKey(blobPath)
        readBlobInfoOrNull(
            store = store,
            key = key,
            password = objectPassword,
        )?.let { existing ->
            return existing.size ?: 0L
        }

        val info = try {
            writeZip(
                store = store,
                key = key,
                mode = BackupWriteMode.Create,
                password = objectPassword,
                entries = listOf(
                    ZipEntryWriter.Sink(
                        name = BLOB_ENTRY,
                        write = write,
                    ),
                ),
            )
        } catch (e: BackupObjectStoreException.AlreadyExists) {
            readBlobInfoOrNull(
                store = store,
                key = key,
                password = objectPassword,
            ) ?: throw e
        }
        return info.size ?: 0L
    }

    override suspend fun writeSnapshot(
        store: BackupObjectStore,
        objectPassword: Password?,
        snapshotId: String,
        manifest: BackupSnapshotManifest,
        vaultJson: String,
    ) {
        try {
            writeZip(
                store = store,
                key = snapshotKey(snapshotId),
                mode = BackupWriteMode.Create,
                password = objectPassword,
                entries = listOf(
                    ZipEntryWriter.Text(
                        name = MANIFEST_ENTRY,
                        text = json.encodeToString(manifest),
                    ),
                    ZipEntryWriter.Text(
                        name = VAULT_ENTRY,
                        text = vaultJson,
                    ),
                ),
            )
        } catch (e: BackupObjectStoreException.AlreadyExists) {
            if (readSnapshotManifest(store, objectPassword, snapshotId) == manifest) {
                return
            }
            throw e
        }
        require(readSnapshotManifest(store, objectPassword, snapshotId) == manifest) {
            "Failed to verify backup snapshot '$snapshotId' after writing."
        }
    }

    override suspend fun listSnapshotIds(
        store: BackupObjectStore,
    ): List<String> = store
        .listAll(BackupObjectKeyPrefix("$SNAPSHOTS_DIR/"))
        .mapNotNull { info ->
            val relative = info.key.value.removePrefix("$SNAPSHOTS_DIR/")
            if (relative.contains('/') || !relative.endsWith(".zip")) {
                return@mapNotNull null
            }
            relative.removeSuffix(".zip")
        }

    override suspend fun readSnapshotManifest(
        store: BackupObjectStore,
        objectPassword: Password?,
        snapshotId: String,
    ): BackupSnapshotManifest? = readZipJsonOrNull(
        store = store,
        key = snapshotKey(snapshotId),
        password = objectPassword,
        entryName = MANIFEST_ENTRY,
    )

    override suspend fun deleteSnapshot(
        store: BackupObjectStore,
        snapshotId: String,
    ) {
        store.delete(snapshotKey(snapshotId))
    }

    override suspend fun deleteBlob(
        store: BackupObjectStore,
        blobPath: String,
    ) {
        store.delete(objectKey(blobPath))
    }

    private suspend inline fun <reified T> readZipJsonOrNull(
        store: BackupObjectStore,
        key: BackupObjectKey,
        password: Password?,
        entryName: String,
    ): T? {
        val text = readZipTextOrNull(
            store = store,
            key = key,
            password = password,
            entryName = entryName,
        ) ?: return null
        return json.decodeFromString(text)
    }

    private suspend fun readZipTextOrNull(
        store: BackupObjectStore,
        key: BackupObjectKey,
        password: Password?,
        entryName: String,
    ): String? {
        if (store.stat(key) == null) {
            return null
        }

        val source = try {
            store.read(key)
        } catch (_: BackupObjectStoreException.NotFound) {
            return null
        }
        return source.use { source ->
            var result: String? = null
            createZipInputStream(source.asInputStream(), password).use { zipStream ->
                while (true) {
                    val header = zipStream.getNextEntry()
                        ?: break
                    if (header.fileName == entryName) {
                        result = zipStream
                            .bufferedReader()
                            .use { it.readText() }
                        break
                    }
                }
            }
            result
        }
    }

    private suspend fun readIndexAtKey(
        store: BackupObjectStore,
        password: Password?,
        key: BackupObjectKey,
    ): BackupIndex? = readZipJsonOrNullCatching(
        store = store,
        key = key,
        password = password,
        entryName = INDEX_ENTRY,
    )

    private suspend fun listIndexGenerationKeys(
        store: BackupObjectStore,
    ): List<IndexGenerationKey> = store
        .listAll(BackupObjectKeyPrefix("$INDEXES_DIR/"))
        .mapNotNull { info ->
            val relative = info.key.value.removePrefix("$INDEXES_DIR/")
            if (relative.contains('/')) {
                return@mapNotNull null
            }
            val match = INDEX_GENERATION_FILE_REGEX.matchEntire(relative)
                ?: return@mapNotNull null
            val generation = match.groupValues[1].toLongOrNull()
                ?: return@mapNotNull null
            IndexGenerationKey(
                key = info.key,
                generation = generation,
                indexId = match.groupValues[2],
            )
        }
        .sortedWith(
            compareByDescending<IndexGenerationKey> { it.generation }
                .thenByDescending { it.key.value },
        )

    private suspend inline fun <reified T> readZipJsonOrNullCatching(
        store: BackupObjectStore,
        key: BackupObjectKey,
        password: Password?,
        entryName: String,
    ): T? = try {
        readZipJsonOrNull(
            store = store,
            key = key,
            password = password,
            entryName = entryName,
        )
    } catch (_: BackupObjectStoreException.NotFound) {
        null
    } catch (e: BackupObjectStoreException) {
        throw e
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        null
    }

    private suspend fun writeZip(
        store: BackupObjectStore,
        key: BackupObjectKey,
        mode: BackupWriteMode,
        password: Password?,
        entries: List<ZipEntryWriter>,
    ): BackupObjectInfo = store.write(
        key = key,
        mode = mode,
    ) { sink ->
        val stream = NonClosingOutputStream(sink.asOutputStream())
        createZipOutputStream(stream, password).use { zipStream ->
            entries.forEach { entry ->
                zipStream.putNextEntry(
                    createZipParameters(
                        fileName = entry.name,
                        password = password,
                    ),
                )
                try {
                    when (entry) {
                        is ZipEntryWriter.Text -> {
                            zipStream.write(entry.text.encodeToByteArray())
                        }

                        is ZipEntryWriter.Sink -> {
                            zipStream.withBufferedSink(entry.write)
                        }
                    }
                } finally {
                    zipStream.closeEntry()
                }
            }
        }
    }

    private suspend fun readBlobInfoOrNull(
        store: BackupObjectStore,
        key: BackupObjectKey,
        password: Password?,
    ): BackupObjectInfo? {
        val info = store.stat(key) ?: return null
        return info.takeIf {
            zipContainsEntry(
                store = store,
                key = key,
                password = password,
                entryName = BLOB_ENTRY,
            )
        }
    }

    private suspend fun zipContainsEntry(
        store: BackupObjectStore,
        key: BackupObjectKey,
        password: Password?,
        entryName: String,
    ): Boolean = try {
        val source = store.read(key)
        source.use { source ->
            createZipInputStream(source.asInputStream(), password).use { zipStream ->
                var found = false
                while (true) {
                    val header = zipStream.getNextEntry()
                        ?: break
                    if (header.fileName == entryName) {
                        found = true
                        break
                    }
                }
                found
            }
        }
    } catch (_: BackupObjectStoreException.NotFound) {
        false
    } catch (e: BackupObjectStoreException) {
        throw e
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        false
    }

    private suspend fun zipContainsReadableEntry(
        store: BackupObjectStore,
        key: BackupObjectKey,
        password: Password?,
        entryName: String,
    ): Boolean {
        val source = store.read(key)
        return source.use { source ->
            createZipInputStream(source.asInputStream(), password).use { zipStream ->
                var found = false
                while (true) {
                    val header = zipStream.getNextEntry()
                        ?: break
                    if (header.fileName == entryName) {
                        zipStream.drain()
                        found = true
                        break
                    }
                }
                found
            }
        }
    }

    private fun java.io.InputStream.drain() {
        val buffer = ByteArray(8192)
        while (read(buffer) != -1) {
        }
    }

    private fun createZipInputStream(
        stream: java.io.InputStream,
        password: Password?,
    ): ZipInputStream = if (password.isNullOrEmpty()) {
        ZipInputStream(stream)
    } else {
        ZipInputStream(stream, requireNotNull(password).value.toCharArray())
    }

    private fun createZipOutputStream(
        stream: OutputStream,
        password: Password?,
    ): ZipOutputStream = if (password.isNullOrEmpty()) {
        ZipOutputStream(stream)
    } else {
        ZipOutputStream(stream, requireNotNull(password).value.toCharArray())
    }

    private fun createZipParameters(
        fileName: String,
        password: Password?,
    ): ZipParameters = ZipParameters().apply {
        compressionMethod = CompressionMethod.DEFLATE
        if (!password.isNullOrEmpty()) {
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            isEncryptFiles = true
        }
        fileNameInZip = fileName
    }

    private fun snapshotKey(
        snapshotId: String,
    ): BackupObjectKey = objectKey("$SNAPSHOTS_DIR/$snapshotId.zip")

    private fun indexGenerationKey(
        index: BackupIndex,
    ): BackupObjectKey {
        val generation = index.generation
            .toString()
            .padStart(20, '0')
        return objectKey("$INDEXES_DIR/$generation-${index.indexId}.zip")
    }

    private fun objectKey(
        value: String,
    ): BackupObjectKey = BackupObjectKey(value)

    private sealed interface ZipEntryWriter {
        val name: String

        data class Text(
            override val name: String,
            val text: String,
        ) : ZipEntryWriter

        data class Sink(
            override val name: String,
            val write: suspend (kotlinx.io.Sink) -> Unit,
        ) : ZipEntryWriter
    }

    private data class IndexGenerationKey(
        val key: BackupObjectKey,
        val generation: Long,
        val indexId: String,
    )

    private class NonClosingOutputStream(
        outputStream: OutputStream,
    ) : FilterOutputStream(outputStream) {
        override fun close() {
            flush()
        }
    }
}

private fun backupRepositoryFeatures(
    password: Password?,
): List<String> {
    val metadataFeature = if (password.isNullOrEmpty()) {
        "plain-zip-metadata"
    } else {
        "encrypted-zip-metadata"
    }
    return listOf(
        metadataFeature,
        "generational-index-zips",
        "authoritative-index",
        "index-snapshot-catalog",
        "index-object-keys",
        "snapshot-vault-json",
        "attachment-blob-zips",
        "random-blob-ids",
    )
}

private fun backupRepositoryCrypto(
    password: Password?,
): BackupRepositoryCrypto {
    val archive = if (password.isNullOrEmpty()) {
        "zip-none"
    } else {
        "zip-aes-256"
    }
    return BackupRepositoryCrypto(
        archive = archive,
        objectArchive = archive,
    )
}

private fun validateBackupRepositoryCrypto(
    metadata: BackupRepositoryMetadata,
    password: Password?,
) {
    val expected = backupRepositoryCrypto(password)
    val matches = metadata.crypto.archive == expected.archive &&
            metadata.crypto.objectArchive == expected.objectArchive
    check(matches) {
        "Backup repository password mode cannot be changed after creation."
    }
}

private fun validateBackupRepositoryMetadata(
    metadata: BackupRepositoryMetadata,
    password: Password?,
) {
    validateBackupRepositoryFormat(metadata)
    validateBackupRepositoryCrypto(
        metadata = metadata,
        password = password,
    )
}

private fun validateBackupRepositoryFormat(
    metadata: BackupRepositoryMetadata,
) {
    val hasCurrentFeatures = CURRENT_REPOSITORY_FEATURES.all { it in metadata.features }
    val hasCurrentLayout = metadata.layout == BackupRepositoryLayout(
        repo = "repo.zip",
        indexes = "indexes/",
        snapshots = "snapshots/",
        blobs = "blobs/",
    )
    check(hasCurrentFeatures && hasCurrentLayout) {
        "Backup repository format is not supported by this version."
    }
}

private val CURRENT_REPOSITORY_FEATURES = setOf(
    "generational-index-zips",
    "authoritative-index",
    "index-snapshot-catalog",
    "index-object-keys",
    "random-blob-ids",
)

private suspend fun BackupObjectStore.listAll(
    prefix: BackupObjectKeyPrefix,
): List<BackupObjectInfo> {
    val items = mutableListOf<BackupObjectInfo>()
    var cursor: BackupListCursor? = null
    do {
        val page = list(
            prefix = prefix,
            cursor = cursor,
        )
        items += page.items
        cursor = page.nextCursor
    } while (cursor != null)
    return items
}

private fun Password?.isNullOrEmpty(): Boolean = this?.value.isNullOrEmpty()
