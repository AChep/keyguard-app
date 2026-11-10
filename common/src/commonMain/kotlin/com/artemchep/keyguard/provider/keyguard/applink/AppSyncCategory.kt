package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * One kind of records linked apps sync, and how its local rows map to entries. Every
 * record has a stable sync id, the id of its entry; the local row keeps it.
 *
 * A category writes its rows directly: what arrives from other devices must not be
 * recorded as a local change again.
 */
internal interface AppSyncCategory {
    /** The kind of the entries of the records. */
    val kind: EntryKind

    /** The switch that turns the sync of the records on this device on or off. */
    val category: DAppLinkSyncCategory

    /** Returns the sync id of every local record. */
    fun readIds(database: Database): Set<String>

    /** Returns the body of the local record [id], or `null` if there is none. */
    fun read(database: Database, id: String): JsonObject?

    /**
     * Writes the record [id] with the [body]: updates it in place, or adds it [now].
     * Returns `false` if the body is not a valid record of the category, or if its rows do
     * not fit the [budget] of the pass. The sync accepts any body within its limits, so
     * the category checks the schema of its records here: an invalid one is never written.
     */
    fun write(database: Database, id: String, body: JsonObject, now: Instant, budget: AppSyncWriteBudget): Boolean

    /** Deletes the local record [id], if there is one, and gives its rows back to the [budget] of the pass. */
    fun delete(database: Database, id: String, budget: AppSyncWriteBudget)

    /** Gives the local record [id] the sync id [newId]: they are the same record. */
    fun changeId(database: Database, id: String, newId: String)
}

/**
 * An [AppSyncCategory] whose records hold blobs, which travel apart from the entries, see
 * [AppLinkBlobs]. The body of an entry names its blob by id. A record whose blob is not
 * here has no local row until the blob arrives: [write] returns `false` for it.
 */
internal interface AppSyncBlobCategory : AppSyncCategory {
    /** Returns the ids of the blobs the local records hold. */
    fun held(database: Database): Set<String>

    /** Returns the blob [hash], or `null` if no local record holds it. */
    fun readBlob(database: Database, hash: String): ByteArray?

    /**
     * Returns the id of the blob the record [id] with the [body] needs, or `null` if the
     * local record holds it, or the record needs none.
     */
    fun missingBlob(database: Database, id: String, body: JsonObject): String?

    /**
     * Returns `true` if the record [id] with the [body] waits for its write: no local row
     * holds its blob yet, also one that needs no transfer. A body that is never written
     * waits for nothing.
     */
    fun isUnwritten(database: Database, id: String, body: JsonObject): Boolean

    /** Returns `true` if the [bytes] of a received blob are well-formed. */
    fun accepts(bytes: ByteArray): Boolean
}

/**
 * The [AppSyncCategory] of each kind of entry linked apps sync. The app uses [default];
 * a test checks that it covers every kind of [SyncScope.APP] but the internal ones, see
 * [EntryKind.internal], and every [DAppLinkSyncCategory] once.
 */
internal class AppSyncCategories(
    val all: List<AppSyncCategory>,
) {
    companion object {
        /**
         * The categories of the app.
         *
         * @param hashOf returns the id of a blob, see
         *   [com.artemchep.keyguard.feature.p2psync.session.BlobExchange.hashOf].
         * @param received the blobs that arrived and no record holds yet.
         */
        fun default(
            hashOf: (ByteArray) -> String,
            received: AppLinkReceivedBlobs,
        ): AppSyncCategories = AppSyncCategories(
            all = listOf(
                EmailForwardersSyncCategory(),
                WordlistsSyncCategory(
                    hashOf = hashOf,
                    received = received,
                ),
                UrlOverridesSyncCategory(),
                UrlBlocksSyncCategory(),
            ),
        )
    }

    private val byKind = all.associateBy { it.kind }

    /** The categories whose records hold blobs. */
    val blobs: List<AppSyncBlobCategory> = all.filterIsInstance<AppSyncBlobCategory>()

    /** Returns the category of the [kind], or `null` if linked apps do not sync it. */
    fun forKind(kind: EntryKind): AppSyncCategory? = byKind[kind]

    /** Returns the category of the [entry], or `null` if this build does not read its kind. */
    fun forEntry(entry: SyncEntry): AppSyncCategory? = entry.knownKind?.let(::forKind)

    /**
     * Returns `true` if this device syncs the [entry] while the [enabled] categories are on:
     * its category is on. An entry of a kind without a category is always synced, so it
     * reaches the devices that read it; no local row ever holds it. Such are a kind this
     * build does not know, a kind of another scope, and a kind internal to the group, see
     * [EntryKind.internal].
     */
    fun syncs(
        entry: SyncEntry,
        enabled: Set<DAppLinkSyncCategory>,
    ): Boolean {
        val category = forEntry(entry)
            ?: return true
        return category.category in enabled
    }
}

/**
 * How many rows of the words of wordlists a sync pass may still write, so that one pass
 * holds the database for a bounded time. The first write of a pass always fits, so a
 * record never waits for good; one that does not fit has no row yet, and a next pass
 * writes it, see [deferred].
 *
 * The device also holds at most [maxHeld] rows in all, whatever the passes write: records
 * of linked apps could otherwise name one large blob a thousand times. A record whose rows
 * do not fit stays without them, and the pass is not [deferred] for it.
 */
internal class AppSyncWriteBudget(
    private val rows: Int,
    private val maxHeld: Long = MAX_HELD_ROWS,
) {
    companion object {
        /** The most rows of words a device holds: four of the largest wordlists. */
        const val MAX_HELD_ROWS: Long = 4L * WordlistBlob.MAX_WORDS
    }

    private var written = 0

    /**
     * The rows the device holds, once counted in this pass, with the writes of the pass;
     * `null` until counted, or once a deletion changed it, see [recount].
     */
    private var held: Long? = null

    /** `true` once a write did not fit: a next pass must run. */
    var deferred: Boolean = false
        private set

    /**
     * `true` if no more rows fit this pass: it wrote all it may, which marks it [deferred].
     * Checked before reading a blob.
     */
    fun skips(): Boolean {
        val full = written > 0 && written >= rows
        if (full) deferred = true
        return full
    }

    /**
     * Takes [count] rows in place of [replaced] rows the device holds; the device's rows are
     * counted with [countHeld] once per pass, and again after a [recount]. Returns `false` if
     * they do not fit: the device would hold more than [maxHeld] rows, or the pass wrote all
     * it may, which marks it [deferred].
     */
    fun take(count: Int, replaced: Long, countHeld: () -> Long): Boolean {
        // Kept also when the rows are refused: every other record of the pass needs the count.
        val before = held ?: countHeld().also { held = it }
        val after = before - replaced + count
        if (after > maxHeld) return false
        val fits = written == 0 || written + count <= rows
        if (fits) {
            written += count
            held = after
        } else {
            deferred = true
        }
        return fits
    }

    /** Counts the rows the device holds again at the next [take]: the pass deleted some. */
    fun recount() {
        held = null
    }
}
