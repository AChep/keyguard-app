package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import java.util.Locale

/**
 * Returns the language of the active keyboard layout as a lowercase
 * ISO 639 code, for example `en` or `ru`, or `null` if it is unknown.
 *
 * Best effort: never throws. Blocking, so call it off the UI thread.
 */
public fun getKeyboardInputLanguage(): String? = runCatching {
    getKeyboardInputLanguageOrNull(
        lib = DesktopLibJna.get(),
    )
}.getOrNull()

/** Language codes by their lowercase English name, for example `russian` to `ru`. */
private val languageCodesByEnglishName: Map<String, String> by lazy {
    // Legacy codes such as `iw` share a name and a language with their replacement.
    Locale.getISOLanguages().associate { code ->
        val locale = Locale.of(code)
        locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) to locale.language
    }
}

/**
 * Turns a native keyboard language into a lowercase ISO 639 code. Accepts a
 * BCP-47 tag (Windows, macOS) or an XKB group name (Linux).
 */
internal fun normalizeKeyboardInputLanguage(
    raw: String,
): String? {
    val value = raw.trim()
    // Group names first, because a few also look like language tags, for
    // example `Lao`.
    return languageOfXkbGroupName(value)
        ?: languageOfTag(value)
}

/**
 * An XKB group name is an English language name, optionally followed by a
 * variant: `English (US)`, `Kurdish, Latin Q`.
 */
private fun languageOfXkbGroupName(name: String): String? {
    val language = name
        .substringBefore('(')
        .substringBefore(',')
        .trim()
        .lowercase(Locale.ROOT)
    return languageCodesByEnglishName[language]
}

private val iso639CodeLength = 2..3

// Ill-formed tags and `und` have an empty language.
private fun languageOfTag(tag: String): String? = Locale
    .forLanguageTag(tag.replace('_', '-'))
    .language
    .takeIf { it.length in iso639CodeLength }
