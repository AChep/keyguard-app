package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What both records of a pairing agree on. */
@Serializable
internal data class BrowserPairingIdentity(
    val id: String,
    /** Base64 of the extension's static X25519 key. */
    val publicKey: String,
    val origin: String,
)

/**
 * The vault's record of the pairings, in the vault's own settings: it
 * confirms the record outside of the vault, see [BrowserPairings]. Never
 * synced or exported, so a new or reset vault confirms no pairing.
 *
 * An unrecognized format is read-only. Sessions report an update requirement
 * and settings show only the records this version can read.
 */
internal class BrowserVaultPairingStore(
    private val store: VaultSettingsKeyValueStore,
) {
    private val preference = store.getString(KEY_PAIRINGS, "")

    /** The pairings the vault confirms. */
    val identities: Flow<Set<BrowserPairingIdentity>> = preference
        .map { value -> read(value).orEmpty().toSet() }

    /** The pairings the vault confirms, or `null` for an unrecognized format; fails if the vault can't be read. */
    suspend fun records(): List<BrowserPairingIdentity>? {
        // Unlike the preference, fails instead of reading as empty. Only this
        // key: each request of a paired session confirms its pairing.
        val value = store.getByKey(KEY_PAIRINGS).bind() ?: return emptyList()
        return (value as? String)?.let(::read)
    }

    /** Replaces the records with [transform] of them; `false`, writing nothing, for an unrecognized format. */
    suspend fun update(
        transform: (List<BrowserPairingIdentity>) -> List<BrowserPairingIdentity>,
    ): Boolean {
        val records = records()
            ?: return false
        val value = VaultJson.encodeToString(PairingsEntity(pairings = transform(records)))
        preference.setAndCommit(value).bind()
        return true
    }

    private fun read(value: String): List<BrowserPairingIdentity>? = if (value.isEmpty()) {
        emptyList()
    } else {
        VaultJson.decodeSchemaOrNull<PairingsEntity>(value, SCHEMA_VERSION)?.pairings
    }

    private companion object {
        const val KEY_PAIRINGS = "browser_extension.pairings"
        const val SCHEMA_VERSION = 1

        val VaultJson = Json { ignoreUnknownKeys = true }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class PairingsEntity(
        @EncodeDefault
        val schemaVersion: Int = SCHEMA_VERSION,
        val pairings: List<BrowserPairingIdentity> = emptyList(),
    )
}
