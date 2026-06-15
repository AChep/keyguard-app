package com.artemchep.keyguard.feature.home.vault.search.engine

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

internal actual fun searchCompatibilityNormalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)

internal actual fun searchDecomposeNormalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)

internal actual fun requiresPlatformWordSegmentation(value: String): Boolean =
    value
        .codePoints()
        .anyMatch { codePoint ->
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
                Character.UnicodeScript.THAI,
                -> true

                else -> false
            }
        }

internal actual fun platformWordSegments(value: String): List<String> {
    val tokens = mutableListOf<String>()
    val iterator = BreakIterator.getWordInstance(Locale.getDefault())
    iterator.setText(value)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val token = value.substring(start, end)
        if (token.any(Char::isLetterOrDigit)) {
            tokens += token
        }
        start = end
        end = iterator.next()
    }
    return tokens
}

internal actual fun forEachPlatformGraphemeCluster(
    value: String,
    block: (startIndex: Int, endIndex: Int) -> Unit,
) {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(value)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        block(start, end)
        start = end
        end = iterator.next()
    }
}
