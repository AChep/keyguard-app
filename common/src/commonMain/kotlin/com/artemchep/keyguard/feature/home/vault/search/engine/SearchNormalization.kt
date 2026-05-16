package com.artemchep.keyguard.feature.home.vault.search.engine

import kotlin.text.CharCategory

private val foldedCharacterReplacements =
    mapOf(
        'ß' to "ss",
        'ẞ' to "ss",
        'æ' to "ae",
        'Æ' to "ae",
        'œ' to "oe",
        'Œ' to "oe",
        'ø' to "o",
        'Ø' to "o",
        'ł' to "l",
        'Ł' to "l",
        'đ' to "d",
        'Đ' to "d",
        'ð' to "d",
        'Ð' to "d",
        'þ' to "th",
        'Þ' to "th",
        'ħ' to "h",
        'Ħ' to "h",
        'ı' to "i",
    )

internal fun SearchTokenizerProfile.hasFoldedAliases(): Boolean =
    when (this) {
        SearchTokenizerProfile.TEXT -> true

        SearchTokenizerProfile.URL,
        SearchTokenizerProfile.HOST,
        SearchTokenizerProfile.EMAIL,
        SearchTokenizerProfile.IDENTIFIER,
        SearchTokenizerProfile.SENSITIVE,
        -> false
    }

internal fun normalizeSearchValue(
    value: String,
    foldAliases: Boolean,
): SearchNormalizedValue {
    val exact =
        value
            .compatibilityNormalize()
            .lowercase()
    val folded =
        if (foldAliases) {
            exact.foldSearchAliases()
        } else {
            exact
        }
    return SearchNormalizedValue(
        exact = exact,
        folded = folded,
    )
}

internal data class SearchNormalizedValue(
    val exact: String,
    val folded: String,
)

private fun String.compatibilityNormalize(): String =
    searchCompatibilityNormalize(this)

private fun String.foldSearchAliases(): String {
    val decomposed = searchDecomposeNormalize(this)
    return buildString(decomposed.length) {
        decomposed.forEach { char ->
            val replacement = foldedCharacterReplacements[char]
            if (replacement != null) {
                append(replacement)
                return@forEach
            }
            when (char.category) {
                CharCategory.NON_SPACING_MARK,
                CharCategory.COMBINING_SPACING_MARK,
                CharCategory.ENCLOSING_MARK,
                -> Unit

                else -> append(char)
            }
        }
    }
}
