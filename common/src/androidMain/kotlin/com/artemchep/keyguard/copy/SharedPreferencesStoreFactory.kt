package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import org.kodein.di.DI

/**
 * @author Artem Chepurnyi
 */
interface SharedPreferencesStoreFactory {
    fun getStore(di: DI, key: Files): KeyValueStore
}
