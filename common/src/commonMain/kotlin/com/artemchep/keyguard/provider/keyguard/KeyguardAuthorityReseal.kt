package com.artemchep.keyguard.provider.keyguard

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.core.store.bitwarden.P2pAuthority
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.feature.p2psync.P2pJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

private const val TAG = "KeyguardAuthorityReseal"

/**
 * The authority key of this device in the group [groupId]: the [sealed] one the database
 * holds, and the same key [resealed] under the new master password.
 */
class KeyguardAuthorityReseal(
    val groupId: String,
    val sealed: P2pAuthority,
    val resealed: P2pAuthority,
)

/**
 * The authority keys of this device, re-sealed under a new master password, see
 * [prepareReseals].
 *
 * @property reseals the keys to write with [commitReseals].
 * @property stale the groups whose key the old master password does not open: this device
 *   can not add or remove devices there.
 */
class KeyguardAuthorityReseals(
    val reseals: List<KeyguardAuthorityReseal>,
    val stale: List<String>,
)

/**
 * Re-seals the authority key of this device in every sync group of the vault [db] under the
 * [newPassword] instead of the [oldPassword], in memory only. The key derivation is slow, so
 * call it before the database is re-keyed, then write the keys with [commitReseals].
 *
 * @throws Exception if the groups can not be read, or a key fails to re-seal for another
 *   reason than the old password not opening it.
 */
suspend fun KeyguardAuthorityKeys.prepareReseals(
    db: VaultDatabaseManager,
    oldPassword: String,
    newPassword: String,
): KeyguardAuthorityReseals {
    val sealed = db
        .get()
        .effectMap(Dispatchers.IO) { database ->
            database.p2pGroupQueries
                .getAll()
                .executeAsList()
                .mapNotNull { row ->
                    val membership = runCatching {
                        P2pJson.decodeFromString(P2pMembership.serializer(), row.membership)
                    }.getOrNull()
                    membership?.device?.authority?.let { row.groupId to it }
                }
        }
        .bind()
    val reseals = mutableListOf<KeyguardAuthorityReseal>()
    val stale = mutableListOf<String>()
    sealed.forEach { (groupId, authority) ->
        val resealed = reseal(authority, oldPassword, newPassword).bind()
        if (resealed != null) {
            reseals += KeyguardAuthorityReseal(groupId, authority, resealed)
        } else {
            stale += groupId
        }
    }
    return KeyguardAuthorityReseals(reseals, stale)
}

/**
 * Writes the [reseals] of [prepareReseals], each one only if its group still holds the key
 * it replaces. Fast: call it once the database was re-keyed.
 *
 * Throws only a fatal error. Returns the groups whose key it did not write although the
 * group still has one: the key changed meanwhile, or the write failed. [onError] hears why
 * a write failed.
 */
suspend fun commitReseals(
    db: VaultDatabaseManager,
    reseals: List<KeyguardAuthorityReseal>,
    onError: suspend (Throwable) -> Unit = {},
): List<String> = reseals
    .filterNot { reseal ->
        runCatchingUnlessCancelled { commitReseal(db, reseal) }
            .getOrElse { e ->
                onError(e)
                false
            }
    }
    .map { it.groupId }

/** Returns `false` if the group has another authority key than the one [reseal] replaces. */
private suspend fun commitReseal(
    db: VaultDatabaseManager,
    reseal: KeyguardAuthorityReseal,
): Boolean = db
    .mutate(TAG) { database ->
        // The group, or its key, is gone: nothing is left to re-seal.
        val current = database.p2pMembershipOrNull(P2pGroupId(reseal.groupId))
            ?: return@mutate true
        val authority = current.device.authority
            ?: return@mutate true
        // The key changed meanwhile; the old password may still seal it.
        if (authority != reseal.sealed) {
            return@mutate false
        }
        val updated = current.copy(device = current.device.copy(authority = reseal.resealed))
        database.p2pGroupQueries.updateMembership(
            membership = P2pJson.encodeToString(P2pMembership.serializer(), updated),
            groupId = reseal.groupId,
        )
        true
    }
    .bind()

/**
 * Re-seals the authority keys of this device as the master password changes, see
 * [prepareReseals] and [commitReseals].
 */
interface KeyguardAuthorityResealer {
    suspend fun prepare(
        db: VaultDatabaseManager,
        oldPassword: String,
        newPassword: String,
    ): KeyguardAuthorityReseals

    suspend fun commit(
        db: VaultDatabaseManager,
        reseals: List<KeyguardAuthorityReseal>,
        onError: suspend (Throwable) -> Unit,
    ): List<String>
}

/** Returns the [KeyguardAuthorityResealer] that re-seals with the [keys]. */
fun KeyguardAuthorityResealer(
    keys: KeyguardAuthorityKeys,
): KeyguardAuthorityResealer = object : KeyguardAuthorityResealer {
    override suspend fun prepare(
        db: VaultDatabaseManager,
        oldPassword: String,
        newPassword: String,
    ) = keys.prepareReseals(db, oldPassword, newPassword)

    override suspend fun commit(
        db: VaultDatabaseManager,
        reseals: List<KeyguardAuthorityReseal>,
        onError: suspend (Throwable) -> Unit,
    ) = commitReseals(db, reseals, onError)
}
