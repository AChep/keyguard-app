package com.artemchep.keyguard.provider.keyguard.sync

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.keyguard.P2pSyncEntry
import com.artemchep.keyguard.feature.p2psync.P2pJson
import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.model.DeviceCaps
import com.artemchep.keyguard.feature.p2psync.model.EntryDelete
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.RosterDevice
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.roster.GroupCaps
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import com.artemchep.keyguard.feature.p2psync.roster.RosterCrdt
import com.artemchep.keyguard.feature.p2psync.roster.RosterSigner
import com.artemchep.keyguard.feature.p2psync.roster.RosterValidator
import com.artemchep.keyguard.provider.keyguard.p2pMembershipOrNull
import com.artemchep.keyguard.provider.keyguard.requireP2pMembership
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Instant

/**
 * Reads and writes the sync state of one sync group: its entries, roster and clock. Call
 * it from inside a database transaction.
 *
 * Versions, deletes, device records and revocations are stored as they were received,
 * every field included, so the ones of a newer build verify and travel on unchanged. So
 * are entries of a kind this build does not know: the app never reads them. The roster is
 * one value in the row of the group, which is read and written whole.
 */
internal class KeyguardSyncStore(
    private val database: Database,
    private val groupId: String,
) {
    private val versionsSerializer = ListSerializer(EntryVersion.serializer())
    private val deletesSerializer = ListSerializer(EntryDelete.serializer())

    /** Returns every entry, by kind and id. */
    fun loadEntries(): MutableMap<EntryKey, SyncEntry> = database.p2pSyncEntryQueries
        .getByGroupId(groupId = groupId)
        .executeAsList()
        .map { row -> row.toEntry() }
        .associateByTo(mutableMapOf()) { it.key }

    /** Returns the entry of the [key], or `null`. */
    fun loadEntry(key: EntryKey): SyncEntry? = database.p2pSyncEntryQueries
        .getByEntryId(groupId = groupId, kind = key.kind, entryId = key.id)
        .executeAsOneOrNull()
        ?.toEntry()

    private fun P2pSyncEntry.toEntry(): SyncEntry = SyncEntry(
        id = entryId,
        kind = kind,
        versions = P2pJson.decodeFromString(versionsSerializer, versions),
        deletes = P2pJson.decodeFromString(deletesSerializer, deletes),
    )

    /** Saves the [entry], with its versions and deletes. */
    fun saveEntry(entry: SyncEntry) {
        database.p2pSyncEntryQueries.upsert(
            groupId = groupId,
            entryId = entry.id,
            kind = entry.kind,
            versions = P2pJson.encodeToString(versionsSerializer, entry.versions),
            deletes = P2pJson.encodeToString(deletesSerializer, entry.deletes),
        )
    }

    /** Returns the roster of the group the [founder] created; an empty one if it has none yet. */
    fun loadRoster(founder: String): Roster = database.p2pGroupQueries
        .getByGroupId(groupId = groupId)
        .executeAsOneOrNull()
        ?.roster
        ?.let { P2pJson.decodeFromString(Roster.serializer(), it) }
        ?.copy(founder = founder)
        ?: Roster(founder = founder)

    /**
     * Replaces the roster, normalized.
     *
     * @throws IllegalStateException if there is no such group.
     */
    fun saveRoster(roster: Roster) {
        val updated = database.p2pGroupQueries.updateRoster(
            roster = P2pJson.encodeToString(Roster.serializer(), roster.normalized()),
            groupId = groupId,
        )
        check(updated.value == 1L) { "There is no sync group $groupId." }
    }

    /** Returns the last stamp the clock of the [node] issued. */
    fun loadClock(node: String): Hlc? = database.p2pClockQueries
        .getByGroupId(groupId = groupId)
        .executeAsOneOrNull()
        ?.let { row ->
            Hlc(
                wall = row.wall,
                counter = row.counter.toInt(),
                node = node,
            )
        }

    /** Saves the last stamp the clock issued. */
    fun saveClock(stamp: Hlc) {
        database.p2pClockQueries.upsert(
            groupId = groupId,
            wall = stamp.wall,
            counter = stamp.counter.toLong(),
        )
    }

    /** Returns the clock of the [node], which goes on from the stored one and stores every stamp it issues. */
    fun clock(
        node: String,
        now: () -> Instant,
    ): HlcClock = HlcClock(
        node = node,
        physicalNow = { now().toEpochMilliseconds() },
        persist = ::saveClock,
        restored = loadClock(node),
    )

    /** Returns the stored roster of the group of [membership], see [LocalRoster]. */
    fun loadValidated(
        membership: P2pMembership,
        crypto: P2pCrypto,
    ): LocalRoster {
        val stored = loadRoster(membership.founderId)
        val validated = RosterValidator(crypto, membership.groupId).validate(stored)
        return LocalRoster(
            store = this,
            membership = membership,
            stored = stored,
            validated = validated,
            crypto = crypto,
        )
    }
}

/**
 * Returns the stored roster of the group [groupId], as this device reads it, see
 * [LocalRoster], or `null` if there is no such group.
 */
internal fun Database.localRosterOrNull(
    groupId: P2pGroupId,
    crypto: P2pCrypto,
): LocalRoster? = p2pMembershipOrNull(groupId)
    ?.let { membership -> KeyguardSyncStore(this, groupId.id).loadValidated(membership, crypto) }

/**
 * Returns the stored roster of the group [groupId], as this device reads it, see [LocalRoster].
 *
 * @throws IllegalArgumentException if there is no such group.
 */
internal fun Database.requireLocalRoster(
    groupId: P2pGroupId,
    crypto: P2pCrypto,
): LocalRoster = KeyguardSyncStore(this, groupId.id)
    .loadValidated(requireP2pMembership(groupId), crypto)

/** The stored roster of a group, as this device, the member of [membership], reads it. */
internal class LocalRoster(
    /** The store of the group: a change saves the roster to it. */
    val store: KeyguardSyncStore,
    val membership: P2pMembership,
    /** The roster as stored, forged records included: a change saves it with the change. */
    val stored: Roster,
    /** The [stored] roster without its forged records. */
    val validated: Roster,
    private val crypto: P2pCrypto,
) {
    val activity = RosterActivity(validated, crypto::sha256)

    /** What every active device supports: this device writes only what they all read. */
    val caps: GroupCaps get() = activity.caps

    /** The record of this device, or `null` if it is not active in the group. */
    val self: RosterDevice? = validated.devices
        .firstOrNull { it.id == membership.device.deviceId }
        ?.takeIf { activity.isActive(it.id) }

    /**
     * The signing key of this device.
     *
     * @throws IllegalArgumentException if it is malformed.
     */
    val signKey: ByteArray get() = membership.device.signPrivateKey()

    /** Returns the clock of this device in the group. */
    fun clock(now: () -> Instant): HlcClock = store.clock(membership.device.deviceId, now)

    /**
     * Signs the record of this device again with the [label] and the [caps], with a newer
     * update stamp, and saves it: the next sync hands it to the other devices. Returns
     * `false`, saving nothing, if the record has them already, or the device is not active
     * in the group.
     */
    fun updateSelf(
        now: () -> Instant,
        label: String = self?.label.orEmpty(),
        caps: DeviceCaps = self?.caps ?: DeviceCaps.CURRENT,
    ): Boolean {
        val record = self
        val changed = record != null && (record.label != label || record.caps != caps)
        if (record != null && changed) {
            val updated = RosterSigner(crypto, membership.groupId).update(
                device = record,
                updatedAt = clock(now).nowAfter(record.updatedAt),
                signPrivateKey = signKey,
                label = label,
                caps = caps,
            )
            // The newer record replaces the current one.
            store.saveRoster(RosterCrdt.merge(stored, Roster(founder = stored.founder, devices = listOf(updated))))
        }
        return changed
    }

    /**
     * Signs the record of this device again with the caps of this build and the features
     * its user turned on, [DeviceCaps.current], if it lists other ones, such as after an
     * update of the app, or once the user turned [relayStorage] on or off: the other devices
     * learn what this one supports with the next sync. The order of the kinds and of the
     * features does not count. Returns `true` if it did.
     */
    fun refreshSelfCaps(now: () -> Instant, relayStorage: Boolean): Boolean {
        val caps = self?.caps
        val current = DeviceCaps.current(relayStorage)
        val outdated = caps != null && !caps.sameAs(current)
        return outdated && updateSelf(now, caps = current)
    }

    /** `true` if these caps list the same as [other], in any order. */
    private fun DeviceCaps.sameAs(other: DeviceCaps): Boolean = rules == other.rules &&
        kinds.toSet() == other.kinds.toSet() &&
        features.toSet() == other.features.toSet()
}

/** The roster of a group once a received roster was merged in, see [mergeRoster]. */
internal class MergedRoster(
    val roster: Roster,
    /** `true` if the stored roster changed. */
    val changed: Boolean,
    /** The stored roster before the merge. */
    val previous: Roster,
    /** `true` if the merge was refused whole, see [RosterValidator.mergeOrNull]: nothing changed. */
    val refused: Boolean = false,
)

/**
 * Merges the [received] roster into the stored roster of the group of [membership]: checks
 * its records against the stored roster, merges them and saves the result without forged
 * records, see [RosterValidator.merge]. Returns `null`, merging nothing, if the roster has
 * another founder, and a [MergedRoster.refused] one if the merge is refused whole. Call it
 * inside a database transaction.
 */
internal fun KeyguardSyncStore.mergeRoster(
    received: Roster,
    membership: P2pMembership,
    crypto: P2pCrypto,
): MergedRoster? {
    val founder = membership.founderId
    if (received.founder != founder) return null
    val localRoster = loadRoster(founder)
    val merged = RosterValidator(crypto, membership.groupId).mergeOrNull(localRoster, received)
    val roster = merged ?: localRoster
    val changed = roster != localRoster
    if (changed) saveRoster(roster)
    return MergedRoster(roster, changed, previous = localRoster, refused = merged == null)
}
