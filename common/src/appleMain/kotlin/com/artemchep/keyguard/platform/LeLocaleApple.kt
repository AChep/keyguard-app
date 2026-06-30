package com.artemchep.keyguard.platform

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleIdentifier
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages

actual object LeLocale {
    actual val languageTag: String
        get() = NSLocale.preferredLanguages
            .firstNotNullOfOrNull { it as? String }
            ?.toLanguageTag()
            ?: NSLocale.currentLocale.localeIdentifier.toLanguageTag()

    actual val iso3Language: String
        get() = NSLocale.currentLocale.languageCode
            .lowercase()
            .let { languageCode ->
                languageCodeToIso3[languageCode] ?: languageCode
            }

    actual fun displayName(languageTag: String): String {
        val localeIdentifier = languageTag.toLocaleIdentifier()
        return NSLocale(localeIdentifier = localeIdentifier)
            .displayNameForKey(
                key = NSLocaleIdentifier,
                value = localeIdentifier,
            )
            ?.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
            ?: languageTag
    }
}

private fun String.toLanguageTag(): String =
    replace('_', '-')

private fun String.toLocaleIdentifier(): String =
    replace('-', '_')

private val languageCodeToIso3 = mapOf(
    "af" to "afr",
    "ar" to "ara",
    "bn" to "ben",
    "ca" to "cat",
    "cs" to "ces",
    "da" to "dan",
    "de" to "deu",
    "el" to "ell",
    "en" to "eng",
    "es" to "spa",
    "fi" to "fin",
    "fr" to "fra",
    "he" to "heb",
    "hu" to "hun",
    "it" to "ita",
    "iw" to "heb",
    "ja" to "jpn",
    "ko" to "kor",
    "nb" to "nob",
    "nl" to "nld",
    "nn" to "nno",
    "no" to "nor",
    "pl" to "pol",
    "pt" to "por",
    "ro" to "ron",
    "ru" to "rus",
    "sr" to "srp",
    "sv" to "swe",
    "tr" to "tur",
    "uk" to "ukr",
    "vi" to "vie",
    "zh" to "zho",
)
