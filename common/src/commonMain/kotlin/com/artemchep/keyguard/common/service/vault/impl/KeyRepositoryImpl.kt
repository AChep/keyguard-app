package com.artemchep.keyguard.common.service.vault.impl

import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.SecureKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getObject
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class KeyRepositoryImpl(
    private val store: KeyValueStore,
    private val json: Json,
    private val base64Service: Base64Service,
) : KeyReadWriteRepository {
    private val dataPref = store.getObject(
        key = "data2",
        defaultValue = null,
        serialize = { key ->
            if (key == null) {
                return@getObject ""
            }

            json.encodeToString(key)
        },
        deserialize = { data ->
            if (data.isBlank()) {
                return@getObject null
            }
            // If any exception happens, we just assume that the data
            // is not present at all. Note: this implicitly clears
            // the local data of the user.
            kotlin.runCatching {
                json.decodeFromString<PersistedSessionEntity>(data)
            }.getOrNull()
        },
    )

    init {
        // Double check that we store the
        // fingerprint in the secure store.
        require(store is SecureKeyValueStore) {
            "Master key repository should be using a secure store. " +
                    "Current use of ${store::class.simpleName} is probably a mistake."
        }
    }

    constructor(directDI: DirectDI) : this(
        store = directDI.instance<Files, KeyValueStore>(arg = Files.KEY),
        json = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun put(session: PersistedSession?) = ioEffect {
        val entity = if (session != null) {
            val masterKeyBase64 = base64Service.encodeToString(session.masterKey.byteArray)
            PersistedSessionEntity(
                masterKeyBase64 = masterKeyBase64,
                createdAt = session.createdAt,
                persistedAt = session.persistedAt,
            )
        } else {
            null
        }
        dataPref.setAndCommit(entity)
    }.flatten()

    override fun get(): Flow<PersistedSession?> = dataPref
        .map { entity ->
            if (entity != null) {
                val masterKey = base64Service.decode(entity.masterKeyBase64)
                PersistedSession(
                    masterKey = MasterKey(masterKey),
                    createdAt = entity.createdAt,
                    persistedAt = entity.persistedAt,
                )
            } else {
                null
            }
        }
}

@Serializable
data class PersistedSessionEntity(
    val masterKeyBase64: String,
    val createdAt: Instant,
    val persistedAt: Instant,
)
