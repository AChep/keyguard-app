package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import org.koin.dsl.module

class AndroidIpcModule {
    val module = module {
        single {
            AndroidIpcRegistrationRepository(
                store = get<KeyValueStoreFactory>().get(Files.ANDROID_IPC),
                json = get(),
                context = get(),
            )
        }
        single<AndroidIpcRegistrationService> {
            get<AndroidIpcRegistrationRepository>()
        }
    }
}
