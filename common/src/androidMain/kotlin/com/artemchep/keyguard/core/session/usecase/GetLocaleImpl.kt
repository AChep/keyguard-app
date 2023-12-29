package com.artemchep.keyguard.core.session.usecase

import androidx.appcompat.app.AppCompatDelegate
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetLocaleAndroid(
    settingsReadRepository: SettingsReadRepository,
) : GetLocale {
    constructor(directDI: DirectDI) : this(
        settingsReadRepository = directDI.instance(),
    )

    override fun invoke(): Flow<String?> = flow<String?> {
        val localeList = AppCompatDelegate.getApplicationLocales()
        val l = (0 until localeList.size())
            .map { localeList[it] }
            .firstOrNull()
            ?.language
        emit(l)
    }
        .flowOn(Dispatchers.Main)
}
