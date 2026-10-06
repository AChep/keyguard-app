package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.serialization.createApplicationJson
import kotlinx.serialization.json.Json
import org.koin.dsl.module

internal class ApplicationSerializationModule {
    val module = module {
        single<Json> { createApplicationJson() }
    }
}
