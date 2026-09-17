package com.artemchep.keyguard.common.service.keyvalue

import com.artemchep.keyguard.common.service.Files

/** Opens an application store with the platform's storage and migration policy. */
fun interface KeyValueStoreFactory {
    fun get(file: Files): KeyValueStore
}
