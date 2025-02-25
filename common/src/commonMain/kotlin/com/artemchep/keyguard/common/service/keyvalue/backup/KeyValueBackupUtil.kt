package com.artemchep.keyguard.common.service.keyvalue.backup

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.findRealKeyValuePreferenceOrNull

typealias KeyValueBackupState = Map<String, Any?>

object KeyValueBackupUtil {
    fun backup(
        prefs: List<KeyValuePreference<*>>,
    ): IO<KeyValueBackupState> = ioEffect {
        prefs
            .mapNotNull { pref ->
                val realPref = pref.findRealKeyValuePreferenceOrNull()
                    ?: return@mapNotNull null
                realPref.toIO()
                    .map { pref.key to it }
            }
            .parallel()
            .map { list ->
                list.toMap()
            }
            .bind()
    }

    fun restore(
        prefs: List<KeyValuePreference<*>>,
        state: KeyValueBackupState,
    ) = ioEffect {
        prefs
            .mapNotNull { pref ->
                val realPref = pref.findRealKeyValuePreferenceOrNull()
                    ?: return@mapNotNull null
                // If the key doesn't exists in a backed
                // up state then do not restore it - simple!
                if (realPref.key !in state) {
                    return@mapNotNull null
                }
                val value = state[realPref.key]
                val valid = realPref.clazz
                    .isInstance(value)
                require(valid) {
                    "Trying to restore a pref '${pref.key}' to a value '$value', " +
                            "expected ${realPref.clazz.simpleName} type!"
                }

                val realPrefFixed = realPref as KeyValuePreference<Any?>
                realPrefFixed.setAndCommit(value)
            }
            .parallel(parallelism = 1)
            .bind()
    }
}
