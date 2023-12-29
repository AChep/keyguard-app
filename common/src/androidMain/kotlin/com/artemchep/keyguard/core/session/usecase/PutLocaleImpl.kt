package com.artemchep.keyguard.core.session.usecase

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.usecase.PutLocale
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PutLocaleAndroid(
    private val settingsReadWriteRepository: SettingsReadWriteRepository,
) : PutLocale {
    constructor(directDI: DirectDI) : this(
        settingsReadWriteRepository = directDI.instance(),
    )

    override fun invoke(locale: String?): IO<Unit> = ioEffect(Dispatchers.Main) {
        val locales = if (locale != null) {
            LocaleListCompat.forLanguageTags(locale)
        } else {
            LocaleListCompat.create()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
