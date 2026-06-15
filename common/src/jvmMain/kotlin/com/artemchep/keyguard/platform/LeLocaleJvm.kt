package com.artemchep.keyguard.platform

import java.util.Locale

actual object LeLocale {
    actual val languageTag: String
        get() = Locale.getDefault().toLanguageTag()

    actual val iso3Language: String
        get() = Locale.getDefault().isO3Language

    actual fun displayName(languageTag: String): String =
        Locale.forLanguageTag(languageTag)
            .let { locale ->
                locale.getDisplayName(locale)
                    .replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase() else char.toString()
                    }
            }
}
