package com.artemchep.keyguard.wear.locale

import com.artemchep.keyguard.build.LocaleConfig
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.GetLocale
import com.artemchep.keyguard.common.usecase.PutLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal class WearLocaleModule {
    val module = module {
        single<AndroidWearLocaleBackend>() bind WearLocaleBackend::class
        single<WearLocaleController>()
        single<GetLocaleWear>() bind GetLocale::class
        single<PutLocaleWear>() bind PutLocale::class
    }
}

internal class GetLocaleWear(
    private val controller: WearLocaleController,
) : GetLocale {
    private val variants = LocaleConfig.locales.associateBy(::normalizeWearLanguageTag)

    override fun invoke(): Flow<String?> = controller.state
        .map { state ->
            // Keep the picker ID for aliases such as iw-IL, which Android returns as he-IL.
            state.language?.let { variants[it] ?: it }
        }
        .distinctUntilChanged()
}

internal class PutLocaleWear(
    private val controller: WearLocaleController,
) : PutLocale {
    override fun invoke(locale: String?): IO<Unit> = ioEffect(Dispatchers.Main) {
        controller.setLanguage(locale)
    }
}
