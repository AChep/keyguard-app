package com.artemchep.keyguard

import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.resolve
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json

internal class AppleKeyValueStoreFactory(
    private val json: Json,
    private val root: AtomicDirectoryDestination,
) : KeyValueStoreFactory {
    private val lock = SynchronizedObject()
    private val stores = mutableMapOf<Files, JsonKeyValueStore>()

    override fun get(file: Files): KeyValueStore = getJson(file)

    /**
     * Each file's live store is shared, so erasure resets the preferences the
     * repositories already hold instead of only deleting the files behind them.
     */
    fun getJson(file: Files): JsonKeyValueStore = synchronized(lock) {
        stores.getOrPut(file) {
            val path = root
                .resolveDirectory(AtomicPathComponent.parse("keyvalue"))
                .resolve(AtomicPathComponent.parse(file.filename))
            JsonKeyValueStore(FileJsonKeyValueStoreStore(io(path), json))
        }
    }
}
