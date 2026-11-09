package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.P2pDevice
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentBundle
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.feature.p2psync.session.BlobExchangeConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The blobs the sessions of a sync group exchange apart from the envelopes, such as the
 * words of the wordlists, by id, see
 * [com.artemchep.keyguard.feature.p2psync.session.BlobExchange]. It hands over what this
 * device holds, and decides which blob each peer session pulls, so that every blob comes
 * from one device at a time.
 */
interface P2pGroupBlobs {
    /** The limits of the blobs of the group. */
    val config: BlobExchangeConfig

    /** Changes whenever a session may find a blob to pull, or this device may hold other blobs. */
    val version: StateFlow<Long>

    /** Returns the ids of the blobs this device hands to its peers. */
    suspend fun held(): Set<String>

    /** Returns the blob [hash], or `null` if this device does not hand it over. */
    suspend fun read(hash: String): ByteArray?

    /** The peer [peerId] holds the blobs [hashes]. */
    fun onPeerBlobs(peerId: String, hashes: Collection<String>)

    /**
     * Claims the next blob to pull from the peer [peerId], if any. The blob [resume], whose
     * pull from the peer was cut short and can go on from what arrived, comes first, also
     * while it is passed over.
     */
    fun claimNext(peerId: String, resume: String? = null): String?

    /**
     * Returns the blobs this device still needs and has not got: the ones it looks for on
     * the relay, as a source of its own, see [claimNext].
     */
    fun wanted(): Set<String>

    /** The blob [hash] arrived from the peer [peerId], and its bytes match its id. */
    suspend fun onReceived(peerId: String, hash: String, bytes: ByteArray)

    /** The peer [peerId] could not hand the blob [hash] over. */
    fun onFailed(peerId: String, hash: String)

    /** The session with the peer [peerId] ended: its transfer can go to another device. */
    fun onSessionEnded(peerId: String)
}

/** Queues a sync pass of the sync group [P2pGroupId]. */
fun interface QueueP2pSync {
    operator fun invoke(groupId: P2pGroupId): IO<Unit>
}

/**
 * One kind of sync group as the shared machinery sees it: the sessions, the enrollment and
 * the device use cases. There is one host per [SyncScope], see [P2pGroupHosts]; what
 * depends on the kind of a group goes through its host.
 *
 * Founding a group is left to the kind: the screen that starts it knows which kind it is.
 */
interface P2pGroupHost {
    /** What the groups of the host sync. */
    val scope: SyncScope

    /**
     * Returns the synced state of the group [groupId] as its peers get it: the entries of
     * its next envelope, tombstones included, and of the bundle of a joining device. Local
     * changes no sync pass committed yet are not in it.
     */
    suspend fun buildLocalState(groupId: P2pGroupId): List<SyncEntry>

    /** Emits once at the start, then whenever the local data of the group [groupId] may have changed. */
    fun localChanges(groupId: P2pGroupId): Flow<Unit>

    /** Queues a sync pass of the group [groupId]. */
    fun queueSync(groupId: P2pGroupId): IO<Unit>

    /** Returns the blobs the sessions of the group [groupId] exchange, or `null` if it has none. */
    fun blobs(groupId: P2pGroupId): P2pGroupBlobs? = null

    /**
     * Checks that this device may join a group of the host.
     *
     * @throws com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingException if
     *   it may not.
     */
    suspend fun checkJoinable()

    /**
     * Stores the group of the [bundle], which admits the [device], with the [network]
     * settings, and returns its id.
     */
    suspend fun import(
        bundle: EnrollmentBundle,
        device: P2pDevice,
        network: P2pNetwork,
    ): P2pGroupId

    /** Takes this device out of the group [groupId]. The records stay on this device. */
    suspend fun dissolve(groupId: P2pGroupId)
}

/**
 * Resolves the [P2pGroupHost] of a sync group by the scope the group is stored with, and
 * queues its sync passes.
 */
class P2pGroupHosts(
    private val db: VaultDatabaseManager,
    hosts: List<P2pGroupHost>,
) : QueueP2pSync {
    private val byScope = hosts.associateBy { it.scope }

    init {
        require(byScope.size == hosts.size) { "Each scope has one host." }
    }

    /**
     * Returns the host of the groups of the [scope].
     *
     * @throws IllegalArgumentException if this app has no such groups.
     */
    fun forScope(scope: SyncScope): P2pGroupHost = requireNotNull(byScope[scope]) {
        "This app has no sync groups of the scope ${scope.wireName}."
    }

    /**
     * Returns the host of the group [groupId].
     *
     * @throws IllegalArgumentException if there is no such group, or this app has no host
     *   of its scope.
     */
    suspend fun forGroup(groupId: P2pGroupId): P2pGroupHost {
        val scope = requireNotNull(scopeOf(groupId)) {
            "There is no sync group ${groupId.id}."
        }
        return forScope(scope)
    }

    /** Queues a sync pass of the group [groupId]; a group that is gone has nothing to sync. */
    override fun invoke(groupId: P2pGroupId): IO<Unit> = ioEffect {
        val scope = scopeOf(groupId)
            ?: return@ioEffect
        forScope(scope)
            .queueSync(groupId)
            .bind()
    }

    private suspend fun scopeOf(groupId: P2pGroupId): SyncScope? = db.get()
        .bind()
        .p2pGroupScopeOrNull(groupId)
}
