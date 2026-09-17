package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.resolve
import kotlinx.serialization.json.Json

class DesktopKeyValueStoreFactory(
    private val dataDirectory: DataDirectory,
    private val json: Json,
) : KeyValueStoreFactory {
    private val stores = mutableMapOf<Files, KeyValueStore>()

    override fun get(file: Files): KeyValueStore = synchronized(stores) {
        stores.getOrPut(file) {
            JsonKeyValueStore(
                FileJsonKeyValueStoreStore(
                    fileIo = dataDirectory.data().map {
                        dataDirectory.atomicDataDirectory()
                            .resolve(AtomicPathComponent.parse(file.filename))
                    },
                    json = json,
                ),
            )
        }
    }
}
