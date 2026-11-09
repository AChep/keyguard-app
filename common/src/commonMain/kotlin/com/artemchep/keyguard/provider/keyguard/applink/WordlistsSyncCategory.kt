package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.data.GeneratorWordlist
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.session.BlobExchange
import com.artemchep.keyguard.feature.p2psync.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * The wordlists of the password generator. The body of an entry is `{"name", "words"}`,
 * where the words are the id of their blob, see [WordlistBlob]; the creation date stays
 * local. A body whose name is longer than [MAX_NAME_LENGTH], or whose words are not a blob
 * id, is not written.
 *
 * The words travel as a blob, apart from the entry. A wordlist whose words are not here
 * yet has no row: it shows up once they arrive, see [AppLinkBlobs].
 *
 * @param hashOf returns the id of a blob.
 * @param received the blobs that arrived and no wordlist holds yet.
 */
// The members of a blob category, and the helpers of the words.
@Suppress("TooManyFunctions")
internal class WordlistsSyncCategory(
    private val hashOf: (ByteArray) -> String,
    private val received: AppLinkReceivedBlobs,
) : AppSyncBlobCategory {
    companion object {
        private const val FIELD_NAME = "name"
        private const val FIELD_WORDS = "words"

        /** The longest name of a wordlist. */
        const val MAX_NAME_LENGTH: Int = 256
    }

    /** The id of the blob of no words, which needs no transfer. */
    private val emptyHash by lazy { hashOf(WordlistBlob.encode(emptyList())) }

    override val kind: EntryKind get() = EntryKind.WORDLIST

    override val category: DAppLinkSyncCategory get() = DAppLinkSyncCategory.WORDLISTS

    override fun readIds(database: Database): Set<String> = database
        .generatorWordlistQueries
        .getSyncIds()
        .executeAsList()
        .toSet()

    override fun read(database: Database, id: String): JsonObject? = database
        .generatorWordlistQueries
        .getBySyncId(syncId = id)
        .executeAsOneOrNull()
        ?.toBody(database)

    override fun write(
        database: Database,
        id: String,
        body: JsonObject,
        now: Instant,
        budget: AppSyncWriteBudget,
    ): Boolean {
        val name = body.nameOrNull()
        val hash = body.wordsOrNull()
        if (name == null || hash == null) return false
        val dao = database.generatorWordlistQueries
        val row = dao.getBySyncId(syncId = id).executeAsOneOrNull()
        return if (row != null && wordsHashOf(database, row) == hash) {
            // In place: the generator refers to a wordlist by its row.
            dao.updateBySyncId(
                name = name,
                syncId = id,
            )
            true
        } else {
            writeWithWords(database, id, name, hash, row, now, budget)
        }
    }

    /**
     * Writes the record [id] with the [name] and the words of the blob [hash], replacing
     * the words of the [row]. Returns `false` if the words are not here yet, or do not fit
     * the [budget]: the record stays as it is until a pass writes it. The budget is checked
     * on the count of the words, before a word is made.
     */
    // The fields of the record.
    @Suppress("LongParameterList")
    private fun writeWithWords(
        database: Database,
        id: String,
        name: String,
        hash: String,
        row: GeneratorWordlist?,
        now: Instant,
        budget: AppSyncWriteBudget,
    ): Boolean {
        val words = wordsToWrite(database, hash, row, budget)
            ?: return false
        val dao = database.generatorWordlistQueries
        val wordlistId = if (row != null) {
            dao.updateBySyncId(
                name = name,
                syncId = id,
            )
            database.generatorWordlistWordQueries.deleteByWordlistId(row.id)
            dao.updateWordsHash(
                wordsHash = hash,
                id = row.id,
            )
            row.id
        } else {
            dao.insert(
                name = name,
                createdAt = now,
                syncId = id,
                wordsHash = hash,
            )
            dao.getBySyncId(syncId = id).executeAsOne().id
        }
        words.forEach { word ->
            database.generatorWordlistWordQueries.insert(
                wordlistId = wordlistId,
                word = word,
            )
        }
        return true
    }

    /**
     * Returns the words of the blob [hash] if they are here and fit the [budget] in place of
     * the words of the [row]. The budget takes the count of the words, so a blob that does
     * not fit is never decoded.
     */
    private fun wordsToWrite(
        database: Database,
        hash: String,
        row: GeneratorWordlist?,
        budget: AppSyncWriteBudget,
    ): List<String>? {
        val words = database.generatorWordlistWordQueries
        val bytes = if (budget.skips()) null else blobOf(database, hash)
        val replaced = row?.let { words.countByWordlistId(wordlistId = it.id).executeAsOne() } ?: 0L
        val fits = bytes != null && budget.take(WordlistBlob.countOf(bytes), replaced) {
            words.count().executeAsOne()
        }
        return bytes
            ?.takeIf { fits }
            ?.let(WordlistBlob::decodeOrNull)
    }

    override fun delete(database: Database, id: String, budget: AppSyncWriteBudget) {
        val deleted = database.generatorWordlistQueries.deleteBySyncId(syncId = id).value
        // Its words went with it.
        if (deleted > 0) budget.recount()
    }

    override fun changeId(database: Database, id: String, newId: String) {
        database.generatorWordlistQueries.updateSyncId(
            newSyncId = newId,
            syncId = id,
        )
    }

    override fun held(database: Database): Set<String> = database
        .generatorWordlistQueries
        .getAll()
        .executeAsList()
        .mapNotNullTo(mutableSetOf()) { it.wordsHash }

    override fun readBlob(database: Database, hash: String): ByteArray? {
        val row = database.generatorWordlistQueries
            .getByWordsHash(wordsHash = hash)
            .executeAsOneOrNull()
            ?: return null
        // A list of more words, or of more bytes, stays on this device: no peer takes it.
        val bytes = wordsOf(database, row)
            .takeIf { it.size <= WordlistBlob.MAX_WORDS }
            ?.let(WordlistBlob::encode)
            ?.takeIf { it.size <= AppLinkBlobs.MAX_BLOB_SIZE }
        // The words changed since the hash was taken.
        return bytes?.takeIf { hashOf(it) == hash }
    }

    override fun missingBlob(
        database: Database,
        id: String,
        body: JsonObject,
    ): String? = unwrittenBlob(database, id, body)?.takeUnless { it == emptyHash }

    override fun isUnwritten(
        database: Database,
        id: String,
        body: JsonObject,
    ): Boolean = unwrittenBlob(database, id, body) != null

    /** Returns the id of the blob of the record [id] with the [body], or `null` if its local row holds it. */
    private fun unwrittenBlob(
        database: Database,
        id: String,
        body: JsonObject,
    ): String? {
        // A record that is never written needs no words.
        val hash = body.wordsOrNull()
            ?.takeUnless { body.nameOrNull() == null }
            ?: return null
        val row = database.generatorWordlistQueries
            .getBySyncId(syncId = id)
            .executeAsOneOrNull()
        val held = row != null && wordsHashOf(database, row) == hash
        return hash.takeUnless { held }
    }

    override fun accepts(bytes: ByteArray): Boolean = WordlistBlob.decodeOrNull(bytes) != null

    /**
     * Returns the blob [hash]: arrived from another device, or held by another local
     * wordlist. Returns `null` if it is not here. Both were checked against the hash
     * already: the blob exchange checks what arrives, and [readBlob] what it reads.
     */
    private fun blobOf(database: Database, hash: String): ByteArray? {
        if (hash == emptyHash) return ByteArray(0)
        return received.get(hash)
            ?: readBlob(database, hash)
    }

    private fun wordsOf(database: Database, row: GeneratorWordlist): List<String> = database
        .generatorWordlistWordQueries
        .getPrimitiveByWordlistId(wordlistId = row.id)
        .executeAsList()

    /** Returns the id of the blob of the words of the [row], taking it first if the row has none yet. */
    private fun wordsHashOf(database: Database, row: GeneratorWordlist): String {
        row.wordsHash?.let { return it }
        val hash = hashOf(WordlistBlob.encode(wordsOf(database, row)))
        database.generatorWordlistQueries.updateWordsHash(
            wordsHash = hash,
            id = row.id,
        )
        return hash
    }

    private fun GeneratorWordlist.toBody(database: Database): JsonObject = buildJsonObject {
        put(FIELD_NAME, name)
        put(FIELD_WORDS, wordsHashOf(database, this@toBody))
    }

    /** The name of the wordlist, or `null` if it is not one. */
    private fun JsonObject.nameOrNull(): String? = string(FIELD_NAME)
        ?.takeIf { it.length <= MAX_NAME_LENGTH }

    /** The id of the blob of the words, or `null` if it is not one. */
    private fun JsonObject.wordsOrNull(): String? = string(FIELD_WORDS)
        // The canonical form only: the relay storage names it, and takes no other.
        ?.takeIf(BlobExchange::isBlobId)
}
