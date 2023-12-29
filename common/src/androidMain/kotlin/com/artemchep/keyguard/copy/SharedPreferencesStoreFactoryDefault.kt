package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class SharedPreferencesStoreFactoryDefault : SharedPreferencesStoreFactory {
    override fun getStore(di: DI, key: Files): KeyValueStore =
        di.direct.instance(
            tag = "proto", // default to secure preferences
            arg = key,
        )
}
