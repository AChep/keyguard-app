package com.artemchep.keyguard.provider.bitwarden.sync.v2.ops

import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.api.SyncEngine
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.OrganizationEntity
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Sync operations for Bitwarden organizations.
 *
 * Organizations are **read-only** from the client's perspective:
 * [pushToServer], [deleteOnServer], and [mergeConflict] all throw
 * [UnsupportedOperationException]. The differ will never produce
 * those actions because organizations use [Instant.DISTANT_FUTURE]
 * as their revision date and are never locally modified.
 *
 * Decryption uses the user's key ([BitwardenCrKey.UserToken]).
 */
class OrganizationSyncOps(
    private val accountId: String,
    private val db: Database,
    private val codec: BitwardenCrCta,
) : EntitySyncOps<BitwardenOrganization, OrganizationEntity> {
    override suspend fun readLocal(localId: String): BitwardenOrganization? =
        db.organizationQueries
            .getByOrganizationId(organizationId = localId)
            .executeAsOneOrNull()
            ?.data_

    override suspend fun insertOrUpdateLocally(entries: List<Pair<OrganizationEntity, BitwardenOrganization?>>) {
        val now = Clock.System.now()
        val decoded =
            entries.map { (server, local) ->
                decodeServerOrganizationOrFallback(
                    server = server,
                    local = local,
                    now = now,
                )
            }
        saveOrganizations(decoded)
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<OrganizationEntity, BitwardenOrganization>>,
    ): LocalUpdateResult {
        val now = Clock.System.now()
        val decoded =
            entries.map { entry ->
                entry to decodeServerOrganizationOrFallback(
                    server = entry.server,
                    local = entry.initialLocal,
                    now = now,
                )
            }
        return updateOrganizationsLocally(decoded)
    }

    private suspend fun decodeServerOrganizationOrFallback(
        server: OrganizationEntity,
        local: BitwardenOrganization?,
        now: Instant,
    ): BitwardenOrganization =
        decodeRemoteOrFallback(
            decode = {
                decodeServerOrganization(server)
            },
            fallback = { e ->
                recordOrganizationDecodeFailure(e)
                val service = server.toDecodingFailedService(now)
                local?.copy(service = service)
                    ?: unsupportedOrganization(
                        server = server,
                        now = now,
                        service = service,
                    )
            },
        )

    private fun decodeServerOrganization(server: OrganizationEntity): BitwardenOrganization =
        BitwardenOrganization
            .encrypted(
                accountId = accountId,
                entity = server,
            )
            .transform(codec)

    private fun OrganizationEntity.toDecodingFailedService(now: Instant) =
        createDecodingFailedService(
            now = now,
            remoteId = id,
            revisionDate = Instant.DISTANT_FUTURE,
            deletedDate = null,
        )

    private fun unsupportedOrganization(
        server: OrganizationEntity,
        now: Instant,
        service: BitwardenService,
    ): BitwardenOrganization =
        BitwardenOrganization(
            accountId = accountId,
            organizationId = server.id,
            revisionDate = now,
            service = service,
            name = "⚠️ Unsupported Organization",
            selfHost = server.selfHost,
        )

    private fun recordOrganizationDecodeFailure(error: Throwable) {
        val logE =
            SyncEngine.DecodeVaultException(
                message = "Failed to decrypt a organization.",
                e = error,
            )
        recordException(logE)
    }

    private fun updateOrganizationsLocally(
        decoded: List<Pair<LocalUpdateEntry<OrganizationEntity, BitwardenOrganization>, BitwardenOrganization>>,
    ): LocalUpdateResult {
        var applied = 0
        var skipped = 0
        db.organizationQueries.transaction {
            decoded.forEach { (entry, organization) ->
                val current =
                    db.organizationQueries
                        .getByOrganizationId(organizationId = entry.localId)
                        .executeAsOneOrNull()
                        ?.data_
                if (entry.writeIfCurrent(current) { insertOrganization(organization) }) {
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

    private fun saveOrganizations(organizations: List<BitwardenOrganization>) {
        db.organizationQueries.transaction {
            organizations.forEach(::insertOrganization)
        }
    }

    private fun insertOrganization(organization: BitwardenOrganization) {
        db.organizationQueries.insert(
            organizationId = organization.organizationId,
            accountId = organization.accountId,
            data = organization,
        )
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        db.organizationQueries.transaction {
            localIds.forEach { organizationId ->
                db.organizationQueries.deleteByOrganizationId(
                    organizationId = organizationId,
                )
            }
        }
    }

    override suspend fun saveLocal(
        local: BitwardenOrganization,
        previousLocal: BitwardenOrganization?,
    ) {
        saveOrganizations(listOf(local))
    }

    override suspend fun pushToServer(
        local: BitwardenOrganization,
        server: OrganizationEntity?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenOrganization> =
        throw UnsupportedOperationException(
            "Organizations are read-only and cannot be pushed to the server.",
        )

    override suspend fun deleteOnServer(
        local: BitwardenOrganization,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenOrganization> =
        throw UnsupportedOperationException(
            "Organizations are read-only and cannot be deleted on the server.",
        )

    override suspend fun mergeConflict(
        local: BitwardenOrganization,
        server: OrganizationEntity,
    ): RemoteWriteOutcome<BitwardenOrganization> =
        throw UnsupportedOperationException(
            "Organizations do not support merge conflict resolution.",
        )
}
