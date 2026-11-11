package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.core.store.bitwarden.P2pMembership
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.merge.EditRefusedException
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.merge.EnvelopeMerger
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.merge.ObjectKeyRotation
import com.artemchep.keyguard.feature.p2psync.merge.Sanitizer
import com.artemchep.keyguard.feature.p2psync.model.CanonicalJson
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.ObjectKey
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.RejectedEntry
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.provider.keyguard.session.revokedPeersToNotify
import com.artemchep.keyguard.provider.keyguard.signPrivateKey
import com.artemchep.keyguard.provider.keyguard.sync.KeyguardSyncStore
import com.artemchep.keyguard.provider.keyguard.sync.LocalRoster
import com.artemchep.keyguard.provider.keyguard.sync.mergeRoster
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * One pass over the records of linked apps. Create it, and run it, inside one database
 * transaction.
 *
 * Local changes are recorded when they are made, see [AppSyncRecorderImpl], so the entries
 * always hold what the local rows hold. A pass merges what other devices sent and writes
 * the result to the rows, and publishes the rows no entry holds yet, such as the ones
 * from before the app was linked.
 *
 * The categories this device does not sync, see [enabled], are left out both ways: their
 * entries from other devices are dropped, and their local entries go to no one, see
 * [AppLinkHost]. Once one is turned on again, the peers are asked for their state again,
 * see [PutAppLinkSyncCategoryImpl], which merges as the edits of a device that was offline do. Entries
 * of a kind without a category, see [AppSyncCategories.syncs], are kept and relayed whatever
 * the categories, but never written to a row: a kind this build does not know, a kind of
 * another scope, and a kind internal to the group, such as its object key, see
 * [ensureObjectKey].
 *
 * A record is published only if every active device supports its kind, see
 * [com.artemchep.keyguard.feature.p2psync.roster.GroupCaps], and its version passes the
 * checks of the peers; else it stays on this device, and the [logRepository] hears why. It
 * also hears what the pass dropped of an envelope, counted by reason, and which entries
 * no category could write to a row, counted by kind.
 *
 * The pass validates the stored roster once, and again only after it merged a roster that
 * changed it.
 */
// The steps of a pass.
@Suppress("TooManyFunctions")
internal class AppLinkSyncPass(
    private val database: Database,
    private val membership: P2pMembership,
    private val crypto: P2pCrypto,
    private val categories: AppSyncCategories,
    private val enabled: Set<DAppLinkSyncCategory>,
    private val now: () -> Instant,
    /** The most words of wordlists the pass writes, see [AppSyncWriteBudget]. */
    maxWords: Int = WordlistBlob.MAX_WORDS,
    private val logRepository: LogRepository? = null,
    /** The stored roster, if the caller validated it in this transaction already. */
    roster: LocalRoster? = null,
) {
    companion object {
        private const val TAG = "AppLinkSyncPass"

        /** The reasons that point at a broken or hostile peer, rather than at a clock or a limit. */
        private val suspectReasons = setOf(
            RejectReason.FORGED,
            RejectReason.UNKNOWN_WRITER,
            RejectReason.MALFORMED,
            RejectReason.INACTIVE_SENDER,
        )
    }

    private val budget = AppSyncWriteBudget(maxWords)

    private val store = KeyguardSyncStore(
        database = database,
        groupId = P2pGroupId.APP_LINK_ID,
    )

    private val publisher = LocalEditPublisher()

    private val envelopeMerger = EnvelopeMerger()

    private val clock = store.clock(membership.device.deviceId, now)

    private val signer = EntrySigner(
        crypto = crypto,
        groupId = membership.groupId,
        signPrivateKey = membership.device.signPrivateKey(),
    )

    private val entries: MutableMap<EntryKey, SyncEntry> = store.loadEntries()

    /** The validated stored roster, or `null` until the pass needs it, or once it changed. */
    private var validatedRoster: LocalRoster? = roster

    private val localRoster: LocalRoster
        get() = validatedRoster
            ?: store.loadValidated(membership, crypto).also { validatedRoster = it }

    /** `true` if a record did not fit the pass: a next pass writes it. */
    val deferred: Boolean get() = budget.deferred

    /** The stored roster before the first roster that changed it, and after the last one. */
    var rosterChange: Pair<Roster, Roster>? = null
        private set

    /**
     * When the writes of each device that the pass dropped as too far in the future are
     * due, by device id: the local time from which the earliest of them would be taken.
     */
    val dueFutureWrites: Map<String, Long> get() = futureWrites

    private val futureWrites = mutableMapOf<String, Long>()

    /** The object key the relay storage of the group uses now, see [ObjectKey.usable]. */
    val objectKey: ObjectKey? get() = ObjectKey.usable(entries[ObjectKey.ENTRY_KEY], localRoster.activity)

    /**
     * `true` if this device is active in the group and has someone to exchange with: another
     * active device, or a removed one that still needs its notice.
     */
    val canExchange: Boolean
        get() {
            val roster = localRoster
            return roster.self != null && (
                roster.activity.activeIds.size > 1 ||
                    roster.validated.revokedPeersToNotify(
                        membership.device.deviceId,
                        crypto,
                        now().toEpochMilliseconds(),
                    ).isNotEmpty()
                )
        }

    /**
     * Merges a [roster] another device sent into the stored one, unless it belongs to
     * another group. Apply it before the envelopes it came with: their entries are checked
     * against the stored roster. Returns `false` if the merge was refused whole: apply none
     * of those envelopes.
     */
    fun applyRoster(roster: Roster): Boolean {
        val merged = store.mergeRoster(roster, membership, crypto)
            ?: return true
        if (merged.changed) {
            rosterChange = (rosterChange?.first ?: merged.previous) to merged.roster
            validatedRoster = null
        }
        return !merged.refused
    }

    /**
     * Merges the entries of the categories this device syncs of the [envelope] the device
     * [from] sent, checked against the stored roster.
     *
     * @param materialize `false` to leave the local rows to a later [materializeAll], such
     *   as when joining: the records this device had may still turn out to be the same.
     */
    fun applyEnvelope(
        from: String,
        envelope: Envelope,
        materialize: Boolean = true,
    ) {
        val sanitizer = Sanitizer(
            roster = localRoster.validated,
            groupId = membership.groupId,
            crypto = crypto,
            nowMillis = now().toEpochMilliseconds(),
        )
        val incoming = envelope.copy(
            entries = envelope.entries.filter { it.isEnabled() },
        )
        val result = envelopeMerger.merge(entries, incoming, from, sanitizer)
        logRejected(from, result.rejected)
        result.validFrom?.let { due ->
            futureWrites[from] = minOf(due, futureWrites[from] ?: due)
        }
        result.newestStamp?.let(clock::receive)
        val refused = mutableListOf<SyncEntry>()
        result.changed.forEach { key ->
            val entry = result.entries.getValue(key)
            entries[key] = entry
            store.saveEntry(entry)
            if (materialize && materialize(entry) == AppSyncWrite.REFUSED) refused += entry
        }
        logRefused(refused)
    }

    /**
     * Publishes the local records no entry holds yet: the ones from before the app was
     * linked. A record with the same body as a live entry no local record holds becomes
     * that entry, so linking two apps that have the same records does not double them.
     */
    fun publishUnrecorded() {
        // Counting the entries encodes every one of them: only a pass that publishes needs it.
        val entryBudget by lazy { EntryBudget.of(entries.values) }
        categories.all.forEach { category ->
            val local = category.readIds(database)
            val unrecorded = local.filter { EntryKey(category.kind, it) !in entries }
            if (unrecorded.isEmpty()) return@forEach
            if (!localRoster.caps.supports(category.kind)) {
                log("Not publishing ${category.kind.wireName}: an active device does not support it")
                return@forEach
            }

            val adoptable = entries.values
                .filter { it.kind == category.kind.wireName && !it.deleted && it.id !in local }
                .groupBy { entry -> entry.body?.let { CanonicalJson.encode(it) } }
                .mapValues { (_, list) -> list.toMutableList() }
            unrecorded.forEach { id ->
                val body = category.read(database, id)
                    ?: return@forEach
                val twin = adoptable[CanonicalJson.encode(body)]?.removeFirstOrNull()
                if (twin != null) {
                    category.changeId(database, id, twin.id)
                } else {
                    publish(category.kind, id, current = null, body, entryBudget)
                }
            }
        }
    }

    /**
     * Publishes a new object key if the group needs one, see [ObjectKeyRotation]: it has
     * none, or this device knows of an inactive device the key does not cover, such as one
     * it just removed. Run it once the pass applied its rosters. A device that is not
     * active writes no key.
     */
    fun ensureObjectKey() {
        val roster = localRoster
        if (roster.self == null) return
        val current = entries[ObjectKey.ENTRY_KEY]
        val body = ObjectKeyRotation.next(
            current = current,
            activity = roster.activity,
            randomBytes = crypto::randomBytes,
        ) ?: return
        publish(EntryKind.OBJECT_KEY, ObjectKey.ENTRY_ID, current, body, EntryBudget.of(entries.values))
    }

    /** Writes every live entry of the categories this device syncs to the local rows. */
    fun materializeAll() {
        val refused = entries.values
            .filter { it.isEnabled() }
            .filter { materialize(it) == AppSyncWrite.REFUSED }
        logRefused(refused)
    }

    /**
     * Writes the live records that have no local row with their blob yet, see
     * [AppSyncBlobCategory.isUnwritten], such as the ones an earlier pass deferred. A record
     * gets its row once its blob arrived, or another record holds it.
     */
    fun materializeUnwritten() {
        entries.values
            .filter { entry -> entry.isEnabled() && !entry.deleted }
            .filter(::isUnwritten)
            .forEach { materialize(it) }
    }

    /** Returns the blobs the live records this device syncs need, and no local row holds. */
    fun missingBlobs(): Set<String> = entries.values
        .filter { entry -> entry.isEnabled() && !entry.deleted }
        .mapNotNullTo(mutableSetOf(), ::missingBlobOf)

    private fun missingBlobOf(entry: SyncEntry): String? {
        val category = categories.forEntry(entry) as? AppSyncBlobCategory
        val body = entry.body
        return if (category != null && body != null) {
            category.missingBlob(database, entry.id, body)
        } else {
            null
        }
    }

    private fun isUnwritten(entry: SyncEntry): Boolean {
        val category = categories.forEntry(entry) as? AppSyncBlobCategory
        val body = entry.body
        return category != null && body != null && category.isUnwritten(database, entry.id, body)
    }

    /** Publishes the [body] as an edit of the [current] entry [id], or as a new one. */
    private fun publish(
        kind: EntryKind,
        id: String,
        current: SyncEntry?,
        body: JsonObject,
        entryBudget: EntryBudget,
    ) {
        val entry = try {
            publisher.publish(
                id = id,
                kind = kind,
                current = current,
                applied = current?.body,
                local = body,
                clock = clock,
                signer = signer,
                budget = entryBudget,
            )
        } catch (e: EditRefusedException) {
            // The peers would drop it: the record stays on this device.
            log("Not publishing ${kind.wireName} $id: ${e.reason}")
            return
        }
        entries[entry.key] = entry
        store.saveEntry(entry)
    }

    private fun log(message: String) {
        logRepository?.post(TAG, message, LogLevel.WARNING)
    }

    /** Logs how many entries of the envelope of the device [from] were [rejected], by reason. */
    private fun logRejected(from: String, rejected: List<RejectedEntry>) {
        if (rejected.isEmpty()) return
        val counts = rejected.groupingBy { it.reason }.eachCount()
        val level = if (counts.keys.any { it in suspectReasons }) LogLevel.WARNING else LogLevel.INFO
        val summary = counts.entries.joinToString { (reason, count) -> "$reason $count" }
        logRepository?.post(TAG, "Dropped entries of device $from: $summary", level)
    }

    /**
     * Writes the [entry] to its local row, or deletes the row of a deleted one. Returns what
     * its category made of the write, or `null` if there was none.
     */
    private fun materialize(entry: SyncEntry): AppSyncWrite? {
        // A kind of a newer build has no rows.
        val category = categories.forEntry(entry)
            ?: return null
        val body = entry.body
        return if (entry.deleted || body == null) {
            category.delete(database, entry.id, budget)
            null
        } else {
            // A body this build can not read keeps its row as it is.
            category.write(database, entry.id, body, now(), budget)
        }
    }

    /** Logs how many of the [entries] their categories refused to write, by kind, see [AppSyncWrite.REFUSED]. */
    private fun logRefused(entries: List<SyncEntry>) {
        if (entries.isEmpty()) return
        val summary = entries
            .groupingBy { it.kind }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString { (kind, count) -> "$kind $count" }
        log("Not written, this build can not read them: $summary")
    }

    private fun SyncEntry.isEnabled(): Boolean = categories.syncs(this, enabled)
}
