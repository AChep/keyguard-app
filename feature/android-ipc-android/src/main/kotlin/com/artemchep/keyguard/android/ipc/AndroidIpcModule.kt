package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

fun androidIpcModule() = DI.Module(
    name = "com.artemchep.keyguard.android.ipc",
) {
    bindSingleton {
        AndroidIpcRegistrationRepository(this)
    }
    bindSingleton<AndroidIpcRegistrationService>(overrides = true) {
        instance<AndroidIpcRegistrationRepository>()
    }
}
