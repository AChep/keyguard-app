package com.artemchep.keyguard.common.service.keyvalue

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.map

interface VaultSettingsKeyValueStore : KeyValueStore {
    /**
     * The value of [key], as [getAll] decodes it, or `null` when there is
     * none. Unlike a preference, which reads as its default, fails when the
     * vault can't be read.
     */
    fun getByKey(key: String): IO<Any?> = getAll().map { it[key] }
}
