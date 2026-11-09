package com.artemchep.keyguard.provider.keyguard

import app.cash.sqldelight.coroutines.asFlow
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Reads and writes the membership of a sync group, which each group keeps in its
 * `p2pGroup` row. The functions that take a [Database] run inside the caller's transaction.
 */
class P2pMembershipStore(
    private val db: VaultDatabaseManager,
) {
    companion object {
        private const val TAG = "P2pMembership"
    }

    /** Returns the membership of the group [groupId], or `null` if there is none. */
    suspend fun get(
        groupId: P2pGroupId,
    ): P2pMembership? = db.get()
        .effectMap { database -> database.p2pMembershipOrNull(groupId) }
        .bind()

    /** Emits the membership of the group [groupId], or `null` while there is none. */
    fun observe(
        groupId: P2pGroupId,
    ): Flow<P2pMembership?> = db.observeP2pGroup(groupId) { it.p2pMembershipOrNull(groupId) }

    /**
     * Replaces the membership of the group [groupId] with the one [transform] returns, and
     * returns it. Writes nothing if it did not change.
     *
     * @throws IllegalArgumentException if there is no such group.
     */
    suspend fun update(
        groupId: P2pGroupId,
        transform: (P2pMembership) -> P2pMembership,
    ): P2pMembership = db
        .mutate(TAG) { database -> update(database, groupId, transform) }
        .bind()

    /** See the suspending [update]; runs inside the caller's transaction. */
    fun update(
        database: Database,
        groupId: P2pGroupId,
        transform: (P2pMembership) -> P2pMembership,
    ): P2pMembership {
        val current = database.requireP2pMembership(groupId)
        val updated = transform(current)
        if (updated != current) {
            database.p2pGroupQueries.updateMembership(
                membership = P2pJson.encodeToString(P2pMembership.serializer(), updated),
                groupId = groupId.id,
            )
        }
        return updated
    }
}

/**
 * Emits what [read] returns from the vault database, read again whenever the membership
 * or the roster of the group [groupId] changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> VaultDatabaseManager.observeP2pGroup(
    groupId: P2pGroupId,
    read: (Database) -> T,
): Flow<T> = get()
    .asFlow()
    .flatMapLatest { database ->
        database.p2pGroupQueries
            .getByGroupId(groupId = groupId.id)
            .asFlow()
            .map { read(database) }
    }
    .flowOn(Dispatchers.Default)
    .distinctUntilChanged()

/** Returns the membership of the group [groupId], or `null` if there is none. */
internal fun Database.p2pMembershipOrNull(
    groupId: P2pGroupId,
): P2pMembership? = p2pGroupQueries
    .getByGroupId(groupId = groupId.id)
    .executeAsOneOrNull()
    ?.membership
    ?.let { json ->
        runCatching { P2pJson.decodeFromString(P2pMembership.serializer(), json) }
            .getOrNull()
    }

/**
 * Returns the membership of the group [groupId].
 *
 * @throws IllegalArgumentException if there is no such group.
 */
internal fun Database.requireP2pMembership(
    groupId: P2pGroupId,
): P2pMembership = requireNotNull(p2pMembershipOrNull(groupId)) {
    "There is no sync group ${groupId.id}."
}

/**
 * Returns the scope of the group [groupId], or `null` if there is no such group or this
 * build does not know its scope.
 */
internal fun Database.p2pGroupScopeOrNull(
    groupId: P2pGroupId,
): SyncScope? = p2pGroupQueries
    .getByGroupId(groupId = groupId.id)
    .executeAsOneOrNull()
    ?.scope
    ?.let(SyncScope::ofWireName)

/**
 * Saves the membership of the group [groupId] of the [scope], creating the group if there
 * is none. Never replaces the row: that would delete everything the group owns.
 */
internal fun Database.saveP2pMembership(
    groupId: P2pGroupId,
    scope: SyncScope,
    membership: P2pMembership,
) {
    p2pGroupQueries.insert(
        groupId = groupId.id,
        scope = scope.wireName,
        membership = P2pJson.encodeToString(P2pMembership.serializer(), membership),
    )
}
