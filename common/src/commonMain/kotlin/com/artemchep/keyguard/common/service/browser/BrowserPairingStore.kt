package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestException
import com.artemchep.keyguard.nativecrypto.NativeCryptoNoise
import com.artemchep.keyguard.nativecrypto.NativeNoiseKeyPair
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * The app's static key pair and the paired extensions. They live outside of
 * the vault, in `Files.BROWSER_EXTENSION`, so that the extensions can learn
 * the vault's state and ask to unlock it while it is locked. Any process that
 * writes the user's files can add a pairing here: while the vault is
 * unlocked, [BrowserPairings] counts only the ones the vault confirms.
 *
 * The private key is stored as plain JSON, as exposed as the extension's own
 * key in the browser's storage.
 *
 * An unrecognized format is read-only. Sessions report an update requirement
 * and settings show only the records this version can read.
 */
class BrowserPairingStore(
    store: KeyValueStore,
    private val json: Json,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()

    private val keyPreference = store.getString(KEY_APP_KEY, "")
    private val pairingsPreference = store.getString(KEY_PAIRINGS, "")

    // Guarded by the mutex.
    private var keyPair: NativeNoiseKeyPair? = null

    /** The paired extensions, oldest first, or `null` for an unrecognized format. */
    internal val records: Flow<List<BrowserPairingRecord>?> = pairingsPreference
        .map { value -> read(value)?.map(PairingEntity::toRecord) }

    /**
     * The app's static key pair, created on first use; `null` while the
     * stored one is unreadable. A newer app may store it otherwise, so it is
     * never replaced.
     */
    suspend fun appKeyPair(): NativeNoiseKeyPair? = mutex.withLock {
        keyPair?.let { return@withLock it }
        val stored = keyPreference.first()
        val created = if (stored.isEmpty()) {
            NativeCryptoNoise.generateKeyPair().also { pair ->
                keyPreference.setAndCommit(Base64.encode(pair.privateKey)).bind()
            }
        } else {
            decodeOrNull(stored)
                ?.takeIf { it.size == KEY_SIZE }
                ?.let(NativeCryptoNoise::keyPairOf)
                ?: return@withLock null
        }
        keyPair = created
        created
    }

    /**
     * The pairing of the extension whose static key is [publicKey], at
     * [origin]: a known key at another origin is not paired there.
     */
    suspend fun find(publicKey: ByteArray, origin: String): BrowserPairing? {
        val key = Base64.encode(publicKey)
        val records = read(pairingsPreference.first()) ?: throw BrowserRequestException(BrowserError.UPDATE_REQUIRED)
        return records.firstOrNull { it.publicKey == key && it.origin == origin }
            ?.toPairing()
    }

    /** The paired extensions, oldest first, or `null` for an unrecognized format. */
    internal suspend fun readRecords(): List<BrowserPairingRecord>? = read(pairingsPreference.first())
        ?.map(PairingEntity::toRecord)

    /**
     * Pairs the extension with [publicKey] at [origin] as the pairing [id],
     * replacing its earlier pairing there; `null`, storing nothing, for a
     * newer schema.
     */
    suspend fun add(
        id: String,
        name: String,
        publicKey: ByteArray,
        origin: String,
    ): BrowserPairing? = mutex.withLock {
        val records = read(pairingsPreference.first())
            ?: return@withLock null
        val key = Base64.encode(publicKey)
        val now = clock.now().toEpochMilliseconds()
        val record = PairingEntity(
            id = id,
            name = name,
            publicKey = key,
            origin = origin,
            createdAt = now,
            lastUsedAt = now,
        )
        val others = records.filterNot { it.publicKey == key && it.origin == origin }
        write(others + record)
        record.toPairing()
    }

    /** Forgets the pairing [id]. */
    suspend fun remove(id: String) = mutex.withLock {
        val records = read(pairingsPreference.first())
            ?: return@withLock
        val remaining = records.filterNot { it.id == id }
        if (remaining.size == records.size) return@withLock
        write(remaining)
    }

    /** Names the pairing [id] [name]. */
    suspend fun rename(id: String, name: String) = mutex.withLock {
        val records = read(pairingsPreference.first())
            ?: return@withLock
        if (records.none { it.id == id }) return@withLock
        write(records.map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Notes that the pairing [id] connected, at most once an hour. */
    suspend fun touch(id: String) = mutex.withLock {
        val records = read(pairingsPreference.first())
            ?: return@withLock
        val now = clock.now().toEpochMilliseconds()
        val record = records.firstOrNull { it.id == id }
            ?: return@withLock
        if (now - record.lastUsedAt < LAST_USED_INTERVAL_MS) return@withLock
        write(records.map { if (it.id == id) it.copy(lastUsedAt = now) else it })
    }

    /** The valid pairings of [value], or `null` for an unrecognized format. */
    private fun read(value: String): List<PairingEntity>? = if (value.isEmpty()) {
        emptyList()
    } else {
        json.decodeSchemaOrNull<PairingsEntity>(value, SCHEMA_VERSION)?.records()
    }

    private suspend fun write(records: List<PairingEntity>) {
        val value = json.encodeToString(PairingsEntity(pairings = records))
        pairingsPreference.setAndCommit(value).bind()
    }

    private companion object {
        const val KEY_APP_KEY = "app_static_key"
        const val KEY_PAIRINGS = "pairings"
        const val SCHEMA_VERSION = 1
        const val KEY_SIZE = 32
        val LAST_USED_INTERVAL_MS = 1.hours.inWholeMilliseconds

        fun decodeOrNull(value: String): ByteArray? = value
            .takeIf(String::isNotEmpty)
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class PairingsEntity(
        @EncodeDefault
        val schemaVersion: Int = SCHEMA_VERSION,
        val pairings: List<PairingEntity> = emptyList(),
    ) {
        /** The valid pairings. */
        fun records(): List<PairingEntity> =
            pairings.filter { decodeOrNull(it.publicKey)?.size == KEY_SIZE && it.id.isNotEmpty() }
    }

    @Serializable
    private data class PairingEntity(
        val id: String,
        val name: String,
        /** Base64 of the extension's static X25519 key. */
        val publicKey: String,
        val origin: String,
        val createdAt: Long,
        val lastUsedAt: Long,
    ) {
        fun toPairing() = BrowserPairing(
            id = id,
            name = name,
            origin = origin,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            lastUsedAt = Instant.fromEpochMilliseconds(lastUsedAt),
        )

        fun toRecord() = BrowserPairingRecord(
            pairing = toPairing(),
            identity = BrowserPairingIdentity(id = id, publicKey = publicKey, origin = origin),
        )
    }
}

/** A pairing outside of the vault, and what the vault's record must match. */
internal class BrowserPairingRecord(
    val pairing: BrowserPairing,
    val identity: BrowserPairingIdentity,
)

/**
 * The JSON object [value] decoded as [T], or `null` if it is not an object of
 * the schema [version] or does not decode. An object without a version is of
 * the first schema.
 */
internal inline fun <reified T> Json.decodeSchemaOrNull(value: String, version: Int): T? =
    parseSchemaOrNull(value, version)
        ?.let { runCatchingNonFatal { decodeFromJsonElement<T>(it) }.getOrNull() }

/** The JSON object [value], or `null` if it is not an object of the schema [version]. */
internal fun Json.parseSchemaOrNull(value: String, version: Int): JsonObject? {
    val entity = runCatchingNonFatal { parseToJsonElement(value) }.getOrNull() as? JsonObject
        ?: return null
    val actual = when (val field = entity["schemaVersion"]) {
        null -> 1
        else -> (field as? JsonPrimitive)?.intOrNull
    }
    return entity.takeIf { actual == version }
}
