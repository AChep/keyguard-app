package com.artemchep.keyguard.core.session.usecase

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.kodein.di.DirectDI

class GetLocaleVariantsAndroid() : GetLocaleVariants {
    constructor(directDI: DirectDI) : this()

    private val sharedFlow = ioEffect(Dispatchers.Main) {
        val locales = mutableListOf<String?>()
        locales += null
        locales += "test"
        locales += getLocales()
        locales
    }
        .shared()
        .asFlow()

    override fun invoke(): Flow<List<String?>> = sharedFlow

    private fun getLocales(): List<String> {
        val locales = kotlin.run {
            val method = AppCompatDelegate::class.java.getDeclaredMethod(
                "getStoredAppLocales",
            )
            method.isAccessible = true
            method.invoke(null) as LocaleListCompat
        }
        return (0 until locales.size())
            .mapNotNull { index -> locales[index] }
            .map { it.language }
    }
}
