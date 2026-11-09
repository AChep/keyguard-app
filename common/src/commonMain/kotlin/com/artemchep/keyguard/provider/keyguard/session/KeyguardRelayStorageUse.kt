package com.artemchep.keyguard.provider.keyguard.session

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Until when the relay may still hold what this device left there for each sync group, in
 * epoch milliseconds: each item it stores moves the time to the end of the lifetime of the
 * item. A device with relay storage off makes no request to the relay storage once the
 * time passed, see [KeyguardMailbox]. Also the blobs of each group this device needs not
 * leave on its own, see [settledBlobs].
 *
 * It is kept on this device apart from the membership, whose every change starts the
 * session over.
 */
interface KeyguardRelayStorageUse {
    /** Returns until when the relay may hold items of the group [groupId], or `null` if it holds none. */
    suspend fun usedUntil(groupId: String): Long?

    /** Sets until when the relay may hold items of the group [groupId]; `null` once it holds none. */
    suspend fun setUsedUntil(groupId: String, epochMs: Long?)

    /**
     * Returns the blobs of the group [groupId] that this device needs not leave on the relay
     * on its own, with the object key they are settled under, or `null` before it first
     * served the group: the ones it left or found there, or held at first. It leaves the
     * others it holds, see [KeyguardObjectMailbox].
     */
    suspend fun settledBlobs(groupId: String): SettledBlobs?

    /** Sets the [settledBlobs] of the group [groupId] under the object key [keyId], in place of any other key. */
    suspend fun setSettledBlobs(groupId: String, keyId: String, hashes: Set<String>)

    companion object {
        /** Kept in memory only, for as long as the returned instance lives. */
        fun inMemory(): KeyguardRelayStorageUse = object : KeyguardRelayStorageUse {
            private val times = MutableStateFlow<Map<String, Long>>(emptyMap())

            override suspend fun usedUntil(groupId: String): Long? = times.value[groupId]

            override suspend fun setUsedUntil(groupId: String, epochMs: Long?) {
                times.update { if (epochMs != null) it + (groupId to epochMs) else it - groupId }
            }

            private val settled = MutableStateFlow<Map<String, Pair<String, Set<String>>>>(emptyMap())

            override suspend fun settledBlobs(groupId: String): SettledBlobs? =
                settled.value[groupId]?.let { (keyId, hashes) -> SettledBlobs(keyId, hashes) }

            override suspend fun setSettledBlobs(groupId: String, keyId: String, hashes: Set<String>) {
                settled.update { it + (groupId to (keyId to hashes)) }
            }
        }
    }
}

/**
 * The blobs this device needs not leave on the relay on its own, see
 * [KeyguardRelayStorageUse.settledBlobs], under the object key [keyId], see
 * [com.artemchep.keyguard.provider.keyguard.transport.GroupObjects.keyId].
 */
class SettledBlobs(
    val keyId: String,
    val hashes: Set<String>,
)

/**
 * The [KeyguardRelayStorageUse] of the vault: keys of the vault settings per group, in the
 * vault database, like the switches of linked apps. The settled blobs are kept as one
 * string, the id of their object key and then their ids, separated by spaces.
 */
class VaultKeyguardRelayStorageUse(
    private val store: VaultSettingsKeyValueStore,
) : KeyguardRelayStorageUse {
    companion object {
        private const val KEY_PREFIX = "p2p.relayStorage.usedUntil."

        private const val SETTLED_KEY_PREFIX = "p2p.relayStorage.settledBlobs."

        private const val NONE = -1L

        /** No blob id holds a dash. */
        private const val NO_SETTLED = "-"

        private const val SEPARATOR = " "
    }

    private fun preference(groupId: String) = store.getLong(KEY_PREFIX + groupId, NONE)

    private fun settledPreference(groupId: String) = store.getString(SETTLED_KEY_PREFIX + groupId, NO_SETTLED)

    override suspend fun settledBlobs(groupId: String): SettledBlobs? = settledPreference(groupId)
        .first()
        .takeIf { it != NO_SETTLED }
        ?.split(SEPARATOR)
        ?.let { ids ->
            SettledBlobs(
                keyId = ids.first(),
                hashes = ids.drop(1).filterTo(mutableSetOf()) { it.isNotEmpty() },
            )
        }

    override suspend fun setSettledBlobs(groupId: String, keyId: String, hashes: Set<String>) {
        settledPreference(groupId)
            .setAndCommit((listOf(keyId) + hashes.sorted()).joinToString(SEPARATOR))
            .bind()
    }

    override suspend fun usedUntil(groupId: String): Long? = preference(groupId)
        .first()
        .takeIf { it != NONE }

    override suspend fun setUsedUntil(groupId: String, epochMs: Long?) {
        val preference = preference(groupId)
        val io = if (epochMs != null) preference.setAndCommit(epochMs) else preference.deleteAndCommit()
        io.bind()
    }
}
