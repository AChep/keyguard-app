package com.artemchep.keyguard.feature.p2psync.merge

import com.artemchep.keyguard.feature.p2psync.clock.Hlc
import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.clock.VersionVector
import com.artemchep.keyguard.feature.p2psync.crypto.P2pCrypto
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.crypto.verifyBase64Url
import com.artemchep.keyguard.feature.p2psync.model.EntryDelete
import com.artemchep.keyguard.feature.p2psync.model.EntryKey
import com.artemchep.keyguard.feature.p2psync.model.EntryVersion
import com.artemchep.keyguard.feature.p2psync.model.Envelope
import com.artemchep.keyguard.feature.p2psync.model.RejectReason
import com.artemchep.keyguard.feature.p2psync.model.RejectedEntry
import com.artemchep.keyguard.feature.p2psync.model.Roster
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.roster.RosterActivity
import kotlin.uuid.Uuid

/** The limits a [Sanitizer] enforces. Every device must enforce the same ones. */
internal object SanitizerLimits {
    /**
     * The largest body of a version, as canonical JSON bytes: 256 KiB. It holds for every
     * kind alike, so no check depends on the schema of a kind.
     */
    const val MAX_BODY_BYTES: Int = 256 * 1024

    /** The most writers a history may have: as many as a roster holds devices. */
    const val MAX_HISTORY_WRITERS: Int = Roster.MAX_DEVICES

    /** The most versions and deletes an entry may hold. */
    const val MAX_ELEMENTS: Int = 64

    /**
     * How far apart device clocks may be: how far in the future a stamp may be, and how
     * long before its admission a device may stamp a write.
     */
    const val MAX_FUTURE_DRIFT_MS: Long = HlcClock.MAX_FUTURE_DRIFT_MS

    /**
     * `true` if [entry] holds at most [MAX_ELEMENTS] versions and deletes: what a device
     * receives, and what it keeps once merged, so its peers take every entry it sends.
     */
    fun admits(entry: SyncEntry): Boolean = entry.versions.size + entry.deletes.size <= MAX_ELEMENTS
}

/**
 * Why the [version] must be dropped by the checks that need neither the roster nor the key
 * of its writer, or `null`: its parts, its body and its history, at the local physical time
 * [nowMillis]. A device runs them on its own writes too, see [LocalEditPublisher].
 */
internal fun versionProblem(version: EntryVersion, nowMillis: Long): RejectReason? {
    // A part was last changed by an edit this version builds on, and not after it.
    val partsValid = version.parts.values.all { stamp -> stamp <= version.stamp && version.history.includes(stamp) }
    return when {
        !partsValid -> RejectReason.INCONSISTENT
        version.bodySize > SanitizerLimits.MAX_BODY_BYTES -> RejectReason.OVERSIZED_BODY
        else -> historyProblem(version.stamp, version.history, nowMillis)
    }
}

/**
 * Why the [delete] must be dropped by the checks that need neither the roster nor the key
 * of its writer, or `null`; see [versionProblem].
 */
internal fun deleteProblem(delete: EntryDelete, nowMillis: Long): RejectReason? =
    historyProblem(delete.stamp, delete.history, nowMillis)

/**
 * Why a version or delete stamped [stamp], with the [history], must be dropped, or `null`.
 * Its history holds the stamp itself, and nothing newer: the clock of its writer had seen
 * every stamp it builds on.
 */
private fun historyProblem(stamp: Hlc, history: VersionVector, nowMillis: Long): RejectReason? {
    val stamps = history.stamps.values
    return when {
        history[stamp.node] != stamp || stamps.any { it > stamp } -> RejectReason.INCONSISTENT
        stamps.size > SanitizerLimits.MAX_HISTORY_WRITERS -> RejectReason.TOO_MANY_WRITERS
        stamp.wall - nowMillis > SanitizerLimits.MAX_FUTURE_DRIFT_MS -> RejectReason.FUTURE_STAMP
        else -> null
    }
}

/** The entries of an envelope a [Sanitizer] let through, and the ones it dropped. */
data class SanitizedEntries(
    /** The entries to merge, without the versions and deletes that were dropped. */
    val accepted: List<SyncEntry>,
    /** The entries that were dropped, or of which versions or deletes were dropped. */
    val rejected: List<RejectedEntry>,
    /** The earliest [CheckedEntry.validFrom] of the entries; `null` if none was dropped as too far in the future. */
    val validFrom: Long? = null,
)

/**
 * What a [Sanitizer] made of one entry: the [entry] with only the versions and deletes that
 * may be merged, or `null` if none may, and why it dropped something, if it did.
 *
 * @property validFrom the local physical time from which the earliest version or delete
 *   dropped as [RejectReason.FUTURE_STAMP] is no longer too far in the future; `null` if
 *   none was.
 */
data class CheckedEntry(
    val entry: SyncEntry?,
    val reason: RejectReason?,
    val validFrom: Long? = null,
)

/**
 * Drops what must not be merged. Whole entries: ids that are not UUIDs, entries the envelope
 * holds more than once, by kind and id, and too many versions and deletes. Single versions
 * and deletes: bodies larger than [SanitizerLimits.MAX_BODY_BYTES], stamps more than the
 * allowed drift in the future, histories that contradict their stamp, writers that had not
 * joined the group of [roster] when they wrote, and signatures that do not verify with the
 * device key of the writer. If the sender is not active, every entry is dropped.
 *
 * No check reads the schema of a kind, or depends on which kinds this build knows: an entry
 * of a kind of a newer build, or of another scope, passes the same checks and is kept and
 * relayed. Only the app decides which kinds it writes to its records.
 *
 * Every check holds for each version and delete alone, so whatever merging accepted ones
 * makes, every device accepts too.
 *
 * Every version and delete is signed by its writer, so a device can only write as itself,
 * whoever relays the write. Its history holds the clock stamps of its writers, none newer
 * than its own, which is never more than the allowed drift in the future: no device can
 * push the history of an entry ahead of the others.
 *
 * No check depends on revocations, which devices learn at different times. A device that
 * merged a write before it learned that its writer was revoked can not take it back, so a
 * device that learned it first has to take the write too, or the two never agree. A revoked
 * device is cut off by the sessions instead: no device that knows of the revocation syncs
 * with it.
 *
 * @param roster the validated roster, merged with the incoming one.
 * @param groupId the id of the sync group, which signatures cover.
 * @param nowMillis the local physical time.
 */
class Sanitizer(
    roster: Roster,
    private val groupId: String,
    private val crypto: P2pCrypto,
    private val nowMillis: Long,
) {
    private val activity = RosterActivity(roster, crypto::sha256)

    /** The device key of each device of the roster, by id. */
    private val signKeys: Map<String, ByteArray> = roster.devices
        .distinctBy { it.id }
        .mapNotNull { device -> P2pEncoding.base64UrlOrNull(device.signPub)?.let { device.id to it } }
        .toMap()

    /**
     * Splits the entries of [envelope], which the device [from] sent, into the ones to merge
     * and the ones to drop. The versions and deletes that the [known] entries, by kind and
     * id, hold already are not checked again.
     */
    fun sanitize(
        envelope: Envelope,
        from: String,
        known: Map<EntryKey, SyncEntry> = emptyMap(),
    ): SanitizedEntries = if (activity.isActive(from)) {
        // An honest device sends each entry once: copies of one entry would merge past the limits.
        val duplicates = envelope.entries
            .groupingBy { it.key }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val checked = envelope.entries.map { entry ->
            val result = if (entry.key in duplicates) {
                CheckedEntry(entry = null, reason = RejectReason.DUPLICATE_ID)
            } else {
                check(entry, known[entry.key])
            }
            entry to result
        }
        SanitizedEntries(
            accepted = checked.mapNotNull { (_, result) -> result.entry },
            rejected = checked.mapNotNull { (entry, result) -> result.reason?.let { RejectedEntry(entry.id, it) } },
            validFrom = checked.mapNotNull { (_, result) -> result.validFrom }.minOrNull(),
        )
    } else {
        SanitizedEntries(
            accepted = emptyList(),
            rejected = envelope.entries.map { RejectedEntry(it.id, RejectReason.INACTIVE_SENDER) },
        )
    }

    /**
     * Returns the [entry] with only the versions and deletes that may be merged, and why the
     * rest was dropped. The ones the [known] state of the entry, of the same kind and id,
     * holds already are not checked again.
     */
    fun check(entry: SyncEntry, known: SyncEntry? = null): CheckedEntry {
        val reason = entryReason(entry)
        if (reason != null) return CheckedEntry(entry = null, reason = reason)

        val trusted = known?.takeIf { it.key == entry.key }
        val knownVersions = trusted?.versions.orEmpty().toSet()
        val knownDeletes = trusted?.deletes.orEmpty().toSet()
        val versions = entry.versions.map { it to (if (it in knownVersions) null else versionReason(entry, it)) }
        val deletes = entry.deletes.map { it to (if (it in knownDeletes) null else deleteReason(entry, it)) }
        val dropped = (versions.mapNotNull { it.second } + deletes.mapNotNull { it.second }).firstOrNull()
        val kept = entry.copy(
            versions = versions.filter { it.second == null }.map { it.first },
            deletes = deletes.filter { it.second == null }.map { it.first },
        )
        // A write too far in the future becomes valid once the clock catches up with it.
        val future = versions.filter { it.second == RejectReason.FUTURE_STAMP }.map { it.first.stamp } +
            deletes.filter { it.second == RejectReason.FUTURE_STAMP }.map { it.first.stamp }
        val validFrom = future.minOfOrNull { it.wall - SanitizerLimits.MAX_FUTURE_DRIFT_MS }
        return if (kept.versions.isEmpty() && kept.deletes.isEmpty()) {
            CheckedEntry(entry = null, reason = dropped, validFrom = validFrom)
        } else {
            CheckedEntry(entry = kept.normalized(), reason = dropped, validFrom = validFrom)
        }
    }

    private fun entryReason(entry: SyncEntry): RejectReason? {
        val count = entry.versions.size + entry.deletes.size
        return when {
            Uuid.parseHexDashOrNull(entry.id) == null -> RejectReason.INVALID_ID
            count == 0 -> RejectReason.INCONSISTENT
            !SanitizerLimits.admits(entry) -> RejectReason.TOO_MANY_WRITERS
            else -> null
        }
    }

    private fun versionReason(entry: SyncEntry, version: EntryVersion): RejectReason? =
        versionProblem(version, nowMillis)
            ?: writersReason(version.history)
            ?: RejectReason.FORGED.takeUnless {
                val bytes = EntrySignatures.versionBytes(groupId, entry.id, entry.kind, version)
                verify(version.writer, bytes, version.sig)
            }

    private fun deleteReason(entry: SyncEntry, delete: EntryDelete): RejectReason? =
        deleteProblem(delete, nowMillis)
            ?: writersReason(delete.history)
            ?: RejectReason.FORGED.takeUnless {
                val bytes = EntrySignatures.deleteBytes(groupId, entry.id, entry.kind, delete)
                verify(delete.writer, bytes, delete.sig)
            }

    /** [RejectReason.UNKNOWN_WRITER] if a writer of the [history] had not joined the group when it wrote. */
    private fun writersReason(history: VersionVector): RejectReason? {
        val drift = SanitizerLimits.MAX_FUTURE_DRIFT_MS
        return RejectReason.UNKNOWN_WRITER.takeIf {
            history.stamps.values.any { !activity.hasJoinedBy(it.node, it, drift) }
        }
    }

    private fun verify(writer: String, bytes: ByteArray, signature: String): Boolean =
        crypto.verifyBase64Url(signKeys[writer], bytes, signature)
}

/** `true` if this history includes the event [stamp] of its node. */
private fun VersionVector.includes(stamp: Hlc): Boolean = this[stamp.node]?.let { it >= stamp } == true
