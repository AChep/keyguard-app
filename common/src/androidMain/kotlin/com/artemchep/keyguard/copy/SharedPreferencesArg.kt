package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore

/**
 * @author Artem Chepurnyi
 */
class SharedPreferencesArg(
    val version: Int,
    val key: Files,
    val store: KeyValueStore? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SharedPreferencesArg

        if (version != other.version) return false
        if (key != other.key) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + key.hashCode()
        return result
    }
}
