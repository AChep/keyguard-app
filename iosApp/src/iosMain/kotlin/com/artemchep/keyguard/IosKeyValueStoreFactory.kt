package com.artemchep.keyguard

import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import com.artemchep.keyguard.common.service.keyvalue.impl.FileJsonKeyValueStoreStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.platform.iosKeyguardAtomicDataDirectory
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.resolve
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json

internal class IosKeyValueStoreFactory(
    private val json: Json,
) : KeyValueStoreFactory {
    private val lock = SynchronizedObject()
    private val stores = mutableMapOf<Files, KeyValueStore>()

    override fun get(file: Files): KeyValueStore = synchronized(lock) {
        stores.getOrPut(file) {
            val path = iosKeyguardAtomicDataDirectory()
                .resolveDirectory(AtomicPathComponent.parse("keyvalue"))
                .resolve(AtomicPathComponent.parse(file.filename))
            JsonKeyValueStore(FileJsonKeyValueStoreStore(io(path), json))
        }
    }
}
