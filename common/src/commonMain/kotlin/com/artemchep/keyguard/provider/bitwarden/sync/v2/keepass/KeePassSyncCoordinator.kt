package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.KeePassCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.KeePassFolderCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.readSoftDeletedDate
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.ops.KeePassCipherSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.ops.KeePassFolderSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.strategy.KeePassCipherSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.strategy.KeePassFolderSyncStrategy

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getEntries
import app.keemobile.kotpass.models.Group
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.exception.KeePassDatabaseModifiedExternallyException
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseMetadata
import com.artemchep.keyguard.common.service.keepass.getKeePassDatabaseMetadata
import com.artemchep.keyguard.common.service.keepass.getPublicCustomDataStringOrNull
import com.artemchep.keyguard.common.service.keepass.getVersionString
import com.artemchep.keyguard.common.service.keepass.openKeePassDatabase
import com.artemchep.keyguard.common.service.keepass.saveKeePassDatabase
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.api.merge
import com.artemchep.keyguard.provider.bitwarden.sync.v2.buildFolderIdMappings
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.throwIfCancellation
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid
import kotlin.time.Clock
import kotlin.time.Instant

class KeePassSyncCoordinator(
    logRepository: LogRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val base32Service: Base32Service,
    private val base64Service: Base64Service,
    private val fileService: FileService,
    private val getPasswordStrength: GetPasswordStrength,
    private val json: Json,
    private val db: VaultDatabaseManager,
    private val webDavClientFactory: WebDavClientFactory? = null,
) {
    companion object {
        private const val TAG = "SyncById.keepass"
    }

    private val diagnostics = KeePassSyncDiagnostics(
        logRepository = logRepository,
    )

    suspend fun sync(token: KeePassToken) {
        val now = Clock.System.now()
        try {
            syncOrThrow(token, now = now)
        } catch (e: Throwable) {
            e.throwIfCancellation()
            // Any abort before the metadata is written — a corrupt or
            // unauthorized open, an external-modification abort, or a
            // file/WebDAV write or commit failure — would otherwise leave a
            // stale "Success". Record the failure (preserving the last
            // successful sync timestamp) so the account surfaces the error,
            // then rethrow. Recording is best-effort so it never masks [e].
            try {
                recordSyncFailure(token, e, now = now)
            } catch (recordError: Throwable) {
                recordError.throwIfCancellation()
            }
            throw e
        }
    }

    private suspend fun syncOrThrow(token: KeePassToken, now: Instant) {
        val keePassDb = openKeePassDatabase(
            token = token,
            fileService = fileService,
            base64Service = base64Service,
            webDavClientFactory = webDavClientFactory,
        )
        val metadataBefore = getDatabaseMetadata(token)
        diagnostics.syncPipelineStarted()

        val mutator = KeePassDbMutator(keePassDb)
        val pipeline = stageSyncPipeline(
            token = token,
            keePassDb = keePassDb,
            mutator = mutator,
            diagnostics = diagnostics,
            now = now,
        )

        // The .kdbx file is the source of truth and is
        // saved to storage once.
        publishKdbxIfNeeded(
            token = token,
            metadataBefore = metadataBefore,
            mutator = mutator,
            diagnostics = diagnostics,
        )

        // Reached only when the flush above did not throw, so SQLite never
        // records an entity as synced before the file is durable.
        publishVaultStagedDeferredWrites(
            buffer = pipeline.buffer,
            diagnostics = diagnostics,
        )

        updateProfile(keePassDb, token)
        updateMetadata(token, pipeline.syncResult, now = now)

        diagnostics.syncResult(pipeline.syncResult)
        diagnostics.syncPipelineCompleted()
    }

    private class PipelineResult(
        val syncResult: SyncResult,
        val buffer: KeePassWriteBackBuffer,
    )

    private suspend fun stageSyncPipeline(
        token: KeePassToken,
        keePassDb: KeePassDatabase,
        mutator: KeePassDbMutator,
        diagnostics: KeePassSyncDiagnostics,
        now: Instant,
    ): PipelineResult {
        val cipherCodec = KeePassCipherCodec(
            cryptoGenerator = cryptoGenerator,
            base32Service = base32Service,
            base64Service = base64Service,
            fileService = fileService,
            getPasswordStrength = getPasswordStrength,
            json = json,
        )
        val folderCodec = KeePassFolderCodec()

        val remoteFolders = extractFolders(keePassDb, now = now)
        if (diagnostics.enabled) {
            // The cipher snapshot is (re-)extracted inside cipherInputs below, after
            // the folder phase has run; here we only report the as-read count.
            diagnostics.databaseExtracted(
                folderCount = remoteFolders.size,
                cipherCount = extractCiphers(keePassDb, now = now).size,
            )
        }

        var buffer: KeePassWriteBackBuffer? = null
        val syncResult = db.mutate(TAG) { localDb ->
            val writeBackBuffer = KeePassWriteBackBuffer(localDb)
            buffer = writeBackBuffer

            val localFolders = localDb.folderQueries
                .getByAccountId(accountId = token.id)
                .executeAsList()
                .map { it.data_ }
            val initialFolderIdMappings = buildFolderIdMappings(
                accountId = token.id,
                folders = localFolders,
            )

            KeePassTreeSyncExecutor(diagnostics).execute(
                folders = KeePassTreeSyncExecutor.FolderInputs(
                    localFolders = localFolders,
                    remoteFolders = remoteFolders,
                    strategy = KeePassFolderSyncStrategy(
                        remoteFolderIdToLocalId = initialFolderIdMappings.remoteToLocalFolders::get,
                    ),
                    ops = KeePassFolderSyncOps(
                        accountId = token.id,
                        buffer = writeBackBuffer,
                        cryptoGenerator = cryptoGenerator,
                        folderCodec = folderCodec,
                        mutator = mutator,
                    ),
                ),
                // Built AFTER folders are applied, so the folder->group-UUID
                // mapping reflects the just-staged groups (a cipher in a folder
                // created during the same sync resolves to the correct group).
                cipherInputs = {
                    val folderIdMappings = buildFolderIdMappings(
                        accountId = token.id,
                        folders = writeBackBuffer.listFolders(accountId = token.id),
                    )
                    val localCiphers = localDb.cipherQueries
                        .getByAccountId(accountId = token.id)
                        .executeAsList()
                        .map { it.data_ }
                    // Re-extracted from the post-folder-phase database so that
                    // entries the folder phase relocated (e.g. ciphers orphaned
                    // to root when their folder was deleted) reconcile against
                    // their NEW group in this same sync, instead of a stale
                    // snapshot that still places them under a removed group.
                    val remoteCiphers = extractCiphers(mutator.database, now = now)
                    KeePassTreeSyncExecutor.CipherInputs(
                        localCiphers = localCiphers,
                        remoteCiphers = remoteCiphers,
                        strategy = KeePassCipherSyncStrategy(
                            remoteFolderIdToLocalId = folderIdMappings.remoteToLocalFolders::get,
                        ),
                        ops = KeePassCipherSyncOps(
                            accountId = token.id,
                            buffer = writeBackBuffer,
                            cryptoGenerator = cryptoGenerator,
                            cipherCodec = cipherCodec,
                            mutator = mutator,
                            remoteToLocalFolders = folderIdMappings.remoteToLocalFolders,
                            localToRemoteFolders = folderIdMappings.localToRemoteFolders,
                        ),
                    )
                },
            )
        }.bind()

        return PipelineResult(
            syncResult = syncResult,
            buffer = requireNotNull(buffer) {
                "Write-back buffer was not initialized."
            },
        )
    }

    /**
     * Flushes the mutated in-memory database to the `.kdbx` file exactly once,
     * guarded by external-modification detection. Throws — aborting
     * the sync so the deferred writes are discarded — if the file changed
     * under us or the write fails.
     */
    private suspend fun publishKdbxIfNeeded(
        token: KeePassToken,
        metadataBefore: KeePassDatabaseMetadata?,
        mutator: KeePassDbMutator,
        diagnostics: KeePassSyncDiagnostics,
    ) {
        if (!mutator.hasMutations) {
            diagnostics.noMutationsToFlush()
            return
        }

        val metadataAfter = getDatabaseMetadata(token)
            .takeIf { metadataAfter ->
                metadataBefore != null && metadataAfter != null &&
                        metadataBefore.isComparableWith(metadataAfter)
            }
        if (metadataAfter == null) {
            // Without comparable metadata we cannot prove the file stayed unchanged
            // while deferred writes were staged, so write-back MAY overwrite an
            // external edit instead of aborting and retrying safely.
            diagnostics.externalModificationGuardUnavailable()
        }
        if (
            metadataBefore != null &&
            metadataAfter != null &&
            metadataBefore.differsFrom(metadataAfter)
        ) {
            val metadataBeforeForLog = metadataBefore.formatForLog()
            val metadataAfterForLog = metadataAfter.formatForLog()
            diagnostics.externalModificationAborted(
                before = metadataBeforeForLog,
                after = metadataAfterForLog,
            )

            val msg = "KeePass database was modified externally during sync: " +
                    "$metadataBeforeForLog -> $metadataAfterForLog), " +
                    "will retry on next sync."
            throw KeePassDatabaseModifiedExternallyException(msg)
        }

        // Clean up: sync updates can leave old
        // entry history snapshots and detached
        // binary blobs behind.
        mutator.cleanupHistory()
        mutator.cleanupUnusedBinaries()
        try {
            saveKeePassDatabase(
                fileService = fileService,
                token = token,
                database = mutator.database,
                base64Service = base64Service,
                webDavClientFactory = webDavClientFactory,
                expectedMetadata = metadataAfter,
            )
        } catch (e: Throwable) {
            e.throwIfCancellation()
            // The file write failed: the staged local writes will not be
            // committed, so they are dropped and re-derived on the next sync.
            diagnostics.writeBackDiscarded(e)
            throw e
        }
        diagnostics.databaseFlushed(mutator.mutationCount)
    }

    /**
     * Applies the buffered local writes to SQLite. Called only after a
     * successful flush, so the committed state always matches the durable file.
     */
    private suspend fun publishVaultStagedDeferredWrites(
        buffer: KeePassWriteBackBuffer,
        diagnostics: KeePassSyncDiagnostics,
    ) {
        if (buffer.isEmpty) return
        db.mutate(TAG) { localDb ->
            buffer.commit(localDb)
        }.bind()
        diagnostics.writeBackCommitted()
    }

    private suspend fun updateProfile(
        keePassDb: KeePassDatabase,
        token: KeePassToken,
    ) {
        val profileName = keePassDb.content.meta.name.takeIf { it.isNotEmpty() }
            ?: keePassDb.header.getPublicCustomDataStringOrNull("KPXC_PUBLIC_NAME")
        val profileColor = keePassDb.content.meta.color?.takeIf { it.isNotEmpty() }
            ?: keePassDb.header.getPublicCustomDataStringOrNull("KPXC_PUBLIC_COLOR")
        val profile = BitwardenProfile(
            accountId = token.id,
            profileId = token.id,
            name = profileName.orEmpty(),
            description = keePassDb.content.meta.description,
            avatarColor = profileColor,
            keyBase64 = base64Service.encodeToString(""),
            privateKeyBase64 = base64Service.encodeToString(""),
            email = keePassDb.content.meta.defaultUser,
            securityStamp = keePassDb.header.cipherId.toString(),
            masterPasswordHint = null,
            masterPasswordHintEnabled = null,
            unofficialServer = false,
            serverVersion = keePassDb.header.getVersionString(),
        )
        db.mutate(TAG) {
            val existingProfile = it.profileQueries
                .getByAccountId(accountId = token.id)
                .executeAsOneOrNull()
            val newMergedProfile = merge(
                remote = profile,
                local = existingProfile?.data_,
            )
            if (newMergedProfile != existingProfile?.data_) {
                it.profileQueries.insert(
                    profileId = newMergedProfile.profileId,
                    accountId = newMergedProfile.accountId,
                    data = newMergedProfile,
                ).await()
            }
        }.bind()
    }

    private suspend fun updateMetadata(
        token: KeePassToken,
        syncResult: SyncResult,
        now: Instant,
    ) {
        // A whole entity type that threw is a hard sync failure. A per-action
        // failure or OCC skip (e.g. one folder that could not be placed, or one
        // cipher with an un-encodable attachment) is isolated: the affected
        // entity carries its own service.error and the sync still succeeds, so
        // a single bad item never blocks the rest or pins the account as
        // perpetually failing.
        val firstEntityTypeFailure = syncResult.outcomes.values
            .filterIsInstance<EntityTypeOutcome.Failed>()
            .firstOrNull()
        if (firstEntityTypeFailure != null) {
            recordSyncFailure(token, firstEntityTypeFailure.error, now = now)
            return
        }
        db.mutate(TAG) {
            val meta = BitwardenMeta(
                accountId = token.id,
                lastSyncTimestamp = now,
                lastSyncResult = BitwardenMeta.LastSyncResult.Success,
            )
            it.metaQueries.insert(accountId = token.id, data = meta)
        }.bind()
    }

    /**
     * Persists a [BitwardenMeta.LastSyncResult.Failure] for the account while
     * preserving the previous successful [BitwardenMeta.lastSyncTimestamp].
     * Shared by [updateMetadata] (an entity type threw inside a completed
     * pipeline) and [sync]'s catch (an abort threw out of the pipeline before
     * any metadata was written), so a failed sync never leaves a stale Success.
     */
    private suspend fun recordSyncFailure(
        token: KeePassToken,
        error: Throwable,
        now: Instant,
    ) {
        db.mutate(TAG) {
            val dao = it.metaQueries
            val existingMeta = dao
                .getByAccountId(accountId = token.id)
                .executeAsList()
                .firstOrNull()
                ?.data_
            val meta = BitwardenMeta(
                accountId = token.id,
                lastSyncTimestamp = existingMeta?.lastSyncTimestamp,
                lastSyncResult = BitwardenMeta.LastSyncResult.Failure(
                    timestamp = now,
                    reason = error.message,
                    // Not currently consumed for KeePass — AccountViewStateProducer
                    // surfaces the re-auth action for any Failure regardless.
                    requiresAuthentication = false,
                ),
            )
            dao.insert(accountId = token.id, data = meta)
        }.bind()
    }

    private suspend fun getDatabaseMetadata(
        token: KeePassToken,
    ): KeePassDatabaseMetadata? = getKeePassDatabaseMetadata(
        fileService = fileService,
        token = token,
        webDavClientFactory = webDavClientFactory,
    )

    private fun KeePassDatabaseMetadata.formatForLog(): String =
        "etag=$etag, lastModified=$lastModified, size=$size"

    private fun getRecycleBinUuid(keePassDb: KeePassDatabase): Uuid? =
        keePassDb.content.meta.recycleBinUuid
            ?.takeIf { keePassDb.content.meta.recycleBinEnabled }

    private fun collectRecycleBinGroupUuids(
        rootGroup: Group,
        recycleBinUuid: Uuid,
    ): Set<Uuid> {
        val result = mutableSetOf<Uuid>()
        fun traverse(group: Group) {
            result += group.uuid
            group.groups.forEach(::traverse)
        }
        rootGroup.groups
            .filter { it.uuid == recycleBinUuid }
            .forEach(::traverse)
        return result
    }

    private fun extractFolders(
        keePassDb: KeePassDatabase,
        now: Instant,
    ): List<KeePassFolder> {
        val recycleBinUuid = getRecycleBinUuid(keePassDb)

        fun getRevisionDate(group: Group): Instant =
            group.times?.lastModificationTime
                ?: group.times?.creationTime
                ?: now

        val result = mutableListOf<KeePassFolder>()
        fun traverse(group: Group, parentGroupUuid: Uuid?) {
            if (recycleBinUuid != null && group.uuid == recycleBinUuid) return
            result += KeePassFolder(
                group = group,
                parentGroupUuid = parentGroupUuid,
                name = group.name,
                revisionDate = getRevisionDate(group),
            )
            group.groups.forEach { child ->
                traverse(child, group.uuid)
            }
        }

        keePassDb.content.group.groups.forEach { group ->
            traverse(group, parentGroupUuid = null)
        }
        return result
    }

    private fun extractCiphers(
        keePassDb: KeePassDatabase,
        now: Instant,
    ): List<KeePassCipher> {
        val recycleBinUuid = getRecycleBinUuid(keePassDb)
        val excludedGroupUuids = if (recycleBinUuid != null) {
            collectRecycleBinGroupUuids(keePassDb.content.group, recycleBinUuid)
        } else {
            emptySet()
        }

        fun getRevisionDate(entry: app.keemobile.kotpass.models.Entry): Instant {
            val revDate = entry.times?.lastModificationTime
                ?: entry.times?.creationTime
            return revDate?.takeIf { it.toEpochMilliseconds() != 0L } ?: now
        }

        return keePassDb.getEntries { true }
            .filter { (group, _) -> group.uuid !in excludedGroupUuids }
            .flatMap { (group, groupEntries) ->
                groupEntries.map { entry ->
                    KeePassCipher(
                        group = group,
                        cipher = entry,
                        revisionDate = getRevisionDate(entry),
                        deletedDate = readSoftDeletedDate(entry),
                    )
                }
            }
    }
}

typealias KeePassFileModifiedExternallyException = KeePassDatabaseModifiedExternallyException
