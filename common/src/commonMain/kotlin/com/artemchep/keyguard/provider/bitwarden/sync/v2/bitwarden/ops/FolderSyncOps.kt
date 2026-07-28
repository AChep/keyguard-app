package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.ops

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.ServerEnvApi
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.api.builder.put
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.FolderUpdate
import com.artemchep.keyguard.provider.bitwarden.entity.request.of
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.writeIfCurrent
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Sync operations for Bitwarden folders.
 *
 * Folders use single-key crypto ([BitwardenCrKey.UserToken]) and
 * support full CRUD: local reads/writes, server POST/PUT/DELETE.
 * Merge conflicts fall back to the server version (folders are
 * not mergeable).
 */
class FolderSyncOps(
    private val accountId: String,
    private val db: Database,
    private val crypto: BitwardenCr,
    private val cryptoGenerator: CryptoGenerator,
    private val httpClient: HttpClient,
    private val env: ServerEnv,
    private val token: String,
    private val foldersApi: ServerEnvApi.Folders,
) : EntitySyncOps<BitwardenFolder, FolderEntity> {
    override suspend fun readLocal(localId: String): BitwardenFolder? =
        db.folderQueries
            .getByFolderId(folderId = localId)
            .executeAsOneOrNull()
            ?.data_

    override suspend fun insertOrUpdateLocally(entries: List<Pair<FolderEntity, BitwardenFolder?>>) {
        val now = Clock.System.now()
        val decoded =
            entries.map { (server, local) ->
                decodeServerFolderOrFallback(
                    server = server,
                    local = local,
                    now = now,
                )
            }
        saveFolders(decoded)
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<FolderEntity, BitwardenFolder>>,
    ): LocalUpdateResult {
        val now = Clock.System.now()
        val decoded =
            entries.map { entry ->
                entry to decodeServerFolderOrFallback(
                    server = entry.server,
                    local = entry.initialLocal,
                    now = now,
                )
            }
        return updateFoldersLocally(decoded)
    }

    private suspend fun decodeServerFolderOrFallback(
        server: FolderEntity,
        local: BitwardenFolder?,
        now: Instant,
    ): BitwardenFolder {
        val folderId = local?.folderId ?: cryptoGenerator.uuid()
        return decodeRemoteOrFallback(
            decode = {
                decodeServerFolder(
                    server = server,
                    folderId = folderId,
                )
            },
            fallback = { e ->
                recordFolderDecodeFailure(server, e)
                val service = server.toDecodingFailedService(now)
                local?.copy(service = service)
                    ?: unsupportedFolder(
                        server = server,
                        folderId = folderId,
                        service = service,
                    )
            },
        )
    }

    private fun decodeServerFolder(
        server: FolderEntity,
        folderId: String,
    ): BitwardenFolder {
        val codec = getCodec(BitwardenCrCta.Mode.DECRYPT)
        return BitwardenFolder
            .encrypted(
                accountId = accountId,
                folderId = folderId,
                entity = server,
            )
            .transform(codec)
    }

    private fun FolderEntity.toDecodingFailedService(now: Instant) =
        createDecodingFailedService(
            now = now,
            remoteId = id,
            revisionDate = revisionDate,
            deletedDate = null,
        )

    private fun unsupportedFolder(
        server: FolderEntity,
        folderId: String,
        service: BitwardenService,
    ): BitwardenFolder =
        BitwardenFolder(
            accountId = accountId,
            folderId = folderId,
            revisionDate = server.revisionDate,
            service = service,
            name = "⚠️ Unsupported Folder",
        )

    private fun recordFolderDecodeFailure(
        server: FolderEntity,
        error: Throwable,
    ) {
        val logObj = mapOf("name" to server.name.take(2))
        val logE =
            DecodeVaultException(
                message = "Failed to decrypt a folder. Structure: $logObj",
                e = error,
            )
        recordException(logE)
    }

    private fun updateFoldersLocally(
        decoded: List<Pair<LocalUpdateEntry<FolderEntity, BitwardenFolder>, BitwardenFolder>>,
    ): LocalUpdateResult {
        var applied = 0
        var skipped = 0
        db.folderQueries.transaction {
            decoded.forEach { (entry, folder) ->
                val current =
                    db.folderQueries
                        .getByFolderId(folderId = entry.localId)
                        .executeAsOneOrNull()
                        ?.data_
                if (entry.writeIfCurrent(current) { insertFolder(folder) }) {
                    applied++
                } else {
                    skipped++
                }
            }
        }
        return LocalUpdateResult(
            applied = applied,
            skipped = skipped,
        )
    }

    private fun saveFolders(folders: List<BitwardenFolder>) {
        db.folderQueries.transaction {
            folders.forEach(::insertFolder)
        }
    }

    private fun insertFolder(folder: BitwardenFolder) {
        db.folderQueries.insert(
            folderId = folder.folderId,
            accountId = folder.accountId,
            data = folder,
        )
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        db.folderQueries.transaction {
            localIds.forEach { folderId ->
                db.folderQueries.deleteByFolderId(
                    folderId = folderId,
                )
            }
        }
    }

    override suspend fun saveLocal(
        local: BitwardenFolder,
        previousLocal: BitwardenFolder?,
    ) {
        saveFolders(listOf(local))
    }

    override suspend fun pushToServer(
        local: BitwardenFolder,
        server: FolderEntity?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenFolder> {
        val encryptor = getCodec(BitwardenCrCta.Mode.ENCRYPT)
        val encryptedFolder = local.transform(encryptor)
        val update = FolderUpdate.of(model = encryptedFolder)

        val folderResponse = when (update) {
            is FolderUpdate.Create -> {
                foldersApi.post(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    body = update.folderRequest,
                )
            }

            is FolderUpdate.Modify -> {
                foldersApi.put(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    id = update.folderId,
                    body = update.folderRequest,
                )
            }
        }

        val decryptCodec = getCodec(BitwardenCrCta.Mode.DECRYPT)
        val decoded = BitwardenFolder
            .encrypted(
                accountId = accountId,
                folderId = local.folderId,
                entity = folderResponse,
            )
            .transform(decryptCodec)
        return RemoteWriteOutcome.Upsert(decoded)
    }

    override suspend fun deleteOnServer(
        local: BitwardenFolder,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenFolder> {
        foldersApi.delete(
            httpClient = httpClient,
            env = env,
            token = token,
            id = serverId,
        )
        return RemoteWriteOutcome.DeleteLocal
    }

    override suspend fun mergeConflict(
        local: BitwardenFolder,
        server: FolderEntity,
    ): RemoteWriteOutcome<BitwardenFolder> {
        val codec = getCodec(BitwardenCrCta.Mode.DECRYPT)
        val decoded = BitwardenFolder
            .encrypted(
                accountId = accountId,
                folderId = local.folderId,
                entity = server,
            )
            .transform(codec)
        return RemoteWriteOutcome.Upsert(decoded)
    }

    private fun getCodec(mode: BitwardenCrCta.Mode): BitwardenCrCta = buildFolderCodec(crypto, mode)
}

/**
 * Builds a codec for folder encrypt/decrypt using the user's
 * symmetric key ([BitwardenCrKey.UserToken]).
 */
internal fun buildFolderCodec(
    crypto: BitwardenCr,
    mode: BitwardenCrCta.Mode,
): BitwardenCrCta =
    buildSyncCodec(
        crypto = crypto,
        mode = mode,
        key = BitwardenCrKey.UserToken,
    )
