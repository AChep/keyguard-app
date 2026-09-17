package com.artemchep.keyguard.copy

import android.content.Context
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import db_key_value.datastore.DataStoreKeyValueStore
import db_key_value.datastore.encrypted.SecureDataStoreKeyValueStore
import db_key_value.datastore.encrypted.SecureStorageCoordinator

/**
 * @author Artem Chepurnyi
 */
class SharedPreferencesStoreFactoryV2 internal constructor(
    private val factoryV1: SharedPreferencesStoreFactory,
    private val plaintextStore: (Files, KeyValueStore) -> KeyValueStore,
    private val encryptedStore: (Files, KeyValueStore) -> KeyValueStore,
) : SharedPreferencesStoreFactory {
    internal constructor(
        factoryV1: SharedPreferencesStoreFactoryV1,
        context: Context,
        logRepository: LogRepository,
        secureStorageCoordinator: SecureStorageCoordinator,
    ) : this(
        factoryV1 = factoryV1,
        plaintextStore = { file, backingStore ->
            DataStoreKeyValueStore(context, file.filename, logRepository, backingStore)
        },
        encryptedStore = { file, backingStore ->
            SecureDataStoreKeyValueStore(
                context, file.filename, logRepository, secureStorageCoordinator, backingStore,
            )
        },
    )

    companion object {
        /**
         * The [Files] stored as plaintext DataStores. Every other entry is routed to an
         * encrypted DataStore; this is the single source of truth for that decision (see
         * `AndroidSecureStorageArtifacts.SECURE_STORE_NAMES`).
         */
        val PLAINTEXT_FILES: Set<Files> =
            setOf(
                Files.SESSION_METADATA,
                Files.WINDOW_STATE,
                Files.DEVICE_ID,
                Files.UI_STATE,
                Files.REVIEW,
                Files.NOTIFICATIONS,
            )
    }

    private val stores = mutableMapOf<Files, KeyValueStore>()

    override fun get(file: Files): KeyValueStore = synchronized(stores) {
        stores.getOrPut(file) {
            val backingStore = factoryV1.get(file)
            if (file in PLAINTEXT_FILES) {
                plaintextStore(file, backingStore)
            } else {
                encryptedStore(file, backingStore)
            }
        }
    }
}
