package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.LocalEnvelope
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.provider.keyguard.P2pGroupBlobs
import com.artemchep.keyguard.provider.keyguard.P2pGroupHosts
import com.artemchep.keyguard.provider.keyguard.observeP2pGroup
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.time.Instant

/** What a peer session records about the peer. */
class KeyguardPeerRecord(
    val deviceId: String,
    val lastSeenAt: Instant,
    val transport: DKeyguardTransport,
)

/**
 * What the sessions of a sync group read and write in the vault database. What depends on
 * the kind of the group goes through its host, see
 * [P2pGroupHosts].
 */
class KeyguardSessionStore(
    private val db: VaultDatabaseManager,
    private val hosts: P2pGroupHosts,
) {
    companion object {
        private const val TAG = "KeyguardSession"
    }

    /**
     * Returns the roster and the envelope of the synced state of the group [groupId], of
     * which this device is a member with the [membership], as the sessions send them; the
     * envelope is hashed with [hasher], see [LocalEnvelope].
     */
    suspend fun localEnvelope(
        groupId: String,
        membership: P2pMembership,
        hasher: (ByteArray) -> ByteArray,
    ): LocalEnvelope {
        val id = P2pGroupId(groupId)
        val entries = hosts
            .forGroup(id)
            .buildLocalState(id)
        // Read after the entries: the roster only grows, so it
        // lists every device an entry of the state came from.
        val database = db.get().bind()
        val roster = KeyguardSyncStore(database, groupId)
            .loadRoster(membership.founderId)
        return LocalEnvelope(
            roster = roster,
            envelope = Envelope(entries = entries),
            hasher = hasher,
        )
    }

    /** Emits once at the start, then whenever the local data of [groupId] may have changed. */
    fun changes(groupId: String): Flow<Unit> = flow {
        val id = P2pGroupId(groupId)
        val host = hosts.forGroup(id)
        val rosterChanges = db.observeP2pGroup(id) { database ->
            database.p2pGroupQueries.getByGroupId(groupId).executeAsOneOrNull()
        }.map { }
        emitAll(merge(host.localChanges(id), rosterChanges))
    }

    /** Returns the blobs the sessions of the group [groupId] exchange, or `null` if it has none. */
    suspend fun blobs(groupId: String): P2pGroupBlobs? {
        val id = P2pGroupId(groupId)
        return hosts
            .forGroup(id)
            .blobs(id)
    }

    /** Returns what the group [groupId] syncs. */
    suspend fun scope(groupId: String): SyncScope = hosts
        .forGroup(P2pGroupId(groupId))
        .scope

    /** Records what this device knows about a peer of [groupId]. */
    suspend fun updatePeer(
        groupId: String,
        record: KeyguardPeerRecord,
    ) {
        db
            .mutate(TAG) { database ->
                database.p2pPeerStateQueries.upsert(
                    groupId = groupId,
                    deviceId = record.deviceId,
                    lastSeenAt = record.lastSeenAt.toEpochMilliseconds(),
                    lastTransport = record.transport.storedValue,
                )
            }
            .bind()
    }
}
