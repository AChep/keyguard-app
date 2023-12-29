package com.artemchep.keyguard.common.service.id.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.service.id.IdRepository
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore

class IdRepositoryImpl(
    store: KeyValueStore,
) : IdRepository {
    companion object {
        private const val KEY = "device_id"
    }

    private val deviceIdPref: KeyValuePreference<String> =
        store.getString(
            key = KEY,
            defaultValue = "",
        )

    override fun put(id: String): IO<Unit> = deviceIdPref
        .setAndCommit(id)

    override fun get(): IO<String> = deviceIdPref.toIO()
}
