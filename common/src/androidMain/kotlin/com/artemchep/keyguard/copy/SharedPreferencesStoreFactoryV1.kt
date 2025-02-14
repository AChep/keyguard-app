package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class SharedPreferencesStoreFactoryV1 : SharedPreferencesStoreFactory {
    companion object {
        private const val VERSION = 1
    }

    override fun getStore(di: DI, key: Files): KeyValueStore =
        di.direct.instance(
            tag = when (key) {
                Files.DEVICE_ID -> SharedPreferencesTypes.SHARED_PREFS
                else -> SharedPreferencesTypes.SHARED_PREFS_ENCRYPTED
            },
            arg = SharedPreferencesArg(
                version = VERSION,
                key = key,
            ),
        )
}
