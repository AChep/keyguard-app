package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.ops

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.CollectionEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.writeIfCurrent
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Sync operations for Bitwarden collections.
 *
 * Collections are **read-only** from the client's perspective:
 * [pushToServer], [deleteOnServer], and [mergeConflict] all throw
 * [UnsupportedOperationException]. The differ will never produce
 * those actions for collections because they use
 * [Instant.DISTANT_FUTURE] as their revision date and are not
 * flagged as locally modified.
 *
 * Decryption uses the owning organization's key
 * ([BitwardenCrKey.OrganizationToken]) when available, falling
 * back to the user's key.
 */
class CollectionSyncOps(
    private val accountId: String,
    private val db: Database,
    private val crypto: BitwardenCr,
) : EntitySyncOps<BitwardenCollection, CollectionEntity> {
    override suspend fun readLocal(localId: String): BitwardenCollection? =
        db.collectionQueries
            .getByCollectionId(collectionId = localId)
            .executeAsOneOrNull()
            ?.data_

    override suspend fun insertOrUpdateLocally(entries: List<Pair<CollectionEntity, BitwardenCollection?>>) {
        val now = Clock.System.now()
        val decoded =
            entries.map { (server, local) ->
                decodeServerCollectionOrFallback(
                    server = server,
                    local = local,
                    now = now,
                )
            }
        saveCollections(decoded)
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<CollectionEntity, BitwardenCollection>>,
    ): LocalUpdateResult {
        val now = Clock.System.now()
        val decoded =
            entries.map { entry ->
                entry to decodeServerCollectionOrFallback(
                    server = entry.server,
                    local = entry.initialLocal,
                    now = now,
                )
            }
        return updateCollectionsLocally(decoded)
    }

    private suspend fun decodeServerCollectionOrFallback(
        server: CollectionEntity,
        local: BitwardenCollection?,
        now: Instant,
    ): BitwardenCollection =
        decodeRemoteOrFallback(
            decode = {
                decodeServerCollection(server)
            },
            fallback = { e ->
                recordCollectionDecodeFailure(e)
                val service = server.toDecodingFailedService(now)
                local?.copy(service = service)
                    ?: unsupportedCollection(
                        server = server,
                        now = now,
                        service = service,
                    )
            },
        )

    private fun decodeServerCollection(server: CollectionEntity): BitwardenCollection {
        val codec = getCodec(organizationId = server.organizationId)
        return BitwardenCollection
            .encrypted(
                accountId = accountId,
                entity = server,
            )
            .transform(codec)
    }

    private fun CollectionEntity.toDecodingFailedService(now: Instant) =
        createDecodingFailedService(
            now = now,
            remoteId = id,
            revisionDate = Instant.DISTANT_FUTURE,
            deletedDate = null,
        )

    private fun unsupportedCollection(
        server: CollectionEntity,
        now: Instant,
        service: BitwardenService,
    ): BitwardenCollection =
        BitwardenCollection(
            accountId = accountId,
            collectionId = server.id,
            externalId = server.externalId,
            organizationId = server.organizationId,
            revisionDate = now,
            service = service,
            name = "⚠️ Unsupported Collection",
            hidePasswords = server.hidePasswords,
            readOnly = server.readOnly,
        )

    private fun recordCollectionDecodeFailure(error: Throwable) {
        val logE =
            DecodeVaultException(
                message = "Failed to decrypt a collection.",
                e = error,
            )
        recordException(logE)
    }

    private fun updateCollectionsLocally(
        decoded: List<Pair<LocalUpdateEntry<CollectionEntity, BitwardenCollection>, BitwardenCollection>>,
    ): LocalUpdateResult {
        var applied = 0
        var skipped = 0
        db.collectionQueries.transaction {
            decoded.forEach { (entry, collection) ->
                val current =
                    db.collectionQueries
                        .getByCollectionId(collectionId = entry.localId)
                        .executeAsOneOrNull()
                        ?.data_
                if (entry.writeIfCurrent(current) { insertCollection(collection) }) {
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

    private fun saveCollections(collections: List<BitwardenCollection>) {
        db.collectionQueries.transaction {
            collections.forEach(::insertCollection)
        }
    }

    private fun insertCollection(collection: BitwardenCollection) {
        db.collectionQueries.insert(
            collectionId = collection.collectionId,
            accountId = collection.accountId,
            data = collection,
        )
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        db.collectionQueries.transaction {
            localIds.forEach { collectionId ->
                db.collectionQueries.deleteByCollectionId(
                    collectionId = collectionId,
                )
            }
        }
    }

    override suspend fun saveLocal(
        local: BitwardenCollection,
        previousLocal: BitwardenCollection?,
    ) {
        saveCollections(listOf(local))
    }

    override suspend fun pushToServer(
        local: BitwardenCollection,
        server: CollectionEntity?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenCollection> =
        throw UnsupportedOperationException(
            "Collections are read-only and cannot be pushed to the server.",
        )

    override suspend fun deleteOnServer(
        local: BitwardenCollection,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenCollection> =
        throw UnsupportedOperationException(
            "Collections are read-only and cannot be deleted on the server.",
        )

    override suspend fun mergeConflict(
        local: BitwardenCollection,
        server: CollectionEntity,
    ): RemoteWriteOutcome<BitwardenCollection> =
        throw UnsupportedOperationException(
            "Collections do not support merge conflict resolution.",
        )

    private fun getCodec(organizationId: String?): BitwardenCrCta = buildCollectionCodec(crypto, organizationId)
}

/**
 * Builds a decrypt-only codec for collections, using the
 * organization's key when [organizationId] is non-null,
 * otherwise falling back to the user's key.
 */
internal fun buildCollectionCodec(
    crypto: BitwardenCr,
    organizationId: String?,
): BitwardenCrCta =
    buildSyncCodec(
        crypto = crypto,
        mode = BitwardenCrCta.Mode.DECRYPT,
        key = syncKeyForOrganization(organizationId),
    )
