package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class SharedPreferencesStoreFactoryV2(
    private val factoryV1: SharedPreferencesStoreFactoryV1,
) : SharedPreferencesStoreFactory {
    companion object {
        private const val VERSION = 2

        /**
         * The [Files] stored as plaintext DataStores. Every other entry is routed to an
         * encrypted DataStore; this is the single source of truth for that decision (see
         * `AndroidSecureStorageArtifacts.SECURE_STORE_NAMES`).
         */
        val PLAINTEXT_FILES: Set<Files> =
            setOf(
                Files.SESSION_METADATA,
                Files.WINDOW_STATE,
                Files.DEVICE_ID,
                Files.UI_STATE,
                Files.REVIEW,
                Files.NOTIFICATIONS,
            )
    }

    constructor(directDI: DirectDI) : this(
        factoryV1 = directDI.instance(),
    )

    override fun getStore(di: DI, key: Files): KeyValueStore = run {
        val arg = SharedPreferencesArg(
            version = VERSION,
            key = key,
            store = factoryV1.getStore(di, key),
        )
        di.direct.instance(
            tag = if (key in PLAINTEXT_FILES) {
                SharedPreferencesTypes.DATA_STORE
            } else {
                SharedPreferencesTypes.DATA_STORE_ENCRYPTED
            },
            arg = arg,
        )
    }
}
