package com.artemchep.keyguard.core.session

import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.PutLocale
import com.artemchep.keyguard.core.session.usecase.GetLocaleAndroid
import com.artemchep.keyguard.core.session.usecase.PutLocaleAndroid
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class AndroidLocaleModule {
    val module = module {
        single<GetLocaleAndroid>() bind GetLocale::class
        single<PutLocaleAndroid>() bind PutLocale::class
    }
}
