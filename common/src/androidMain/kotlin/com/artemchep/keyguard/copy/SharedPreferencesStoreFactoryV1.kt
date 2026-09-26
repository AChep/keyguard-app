package com.artemchep.keyguard.copy

import android.content.Context
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import db_key_value.shared_prefs.SharedPrefsKeyValueStore
import db_key_value.shared_prefs.encrypted.SecureSharedPrefsKeyValueStore

/**
 * @author Artem Chepurnyi
 */
class SharedPreferencesStoreFactoryV1 internal constructor(
    private val plaintextStore: (Files) -> KeyValueStore,
    private val encryptedStore: (Files) -> KeyValueStore,
) : SharedPreferencesStoreFactory {
    constructor(
        context: Context,
        logRepository: LogRepository,
    ) : this(
        plaintextStore = { file -> SharedPrefsKeyValueStore(context, file.filename, logRepository) },
        encryptedStore = { file -> SecureSharedPrefsKeyValueStore(context, file.filename, logRepository) },
    )

    private val stores = mutableMapOf<Files, KeyValueStore>()

    override fun get(file: Files): KeyValueStore = synchronized(stores) {
        stores.getOrPut(file) {
            if (file == Files.DEVICE_ID) plaintextStore(file) else encryptedStore(file)
        }
    }
}
