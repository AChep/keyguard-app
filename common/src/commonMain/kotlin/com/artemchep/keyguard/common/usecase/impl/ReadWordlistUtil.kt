package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.WordlistWordError
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

object ReadWordlistUtil {
    /** The longest word of a wordlist. */
    const val MAX_WORD_LENGTH: Int = 512

    fun Sequence<String>.parseAsWordlist(): ImmutableList<String> = filter {
        it.isNotBlank() &&
                !it.startsWith('#') &&
                !it.startsWith(';') &&
                !it.startsWith('-') &&
                !it.startsWith('/')
    }.toImmutableList()

    context(text: String)
    fun parseAsWordlist(): ImmutableList<String> = text.lineSequence()
        .parseAsWordlist()

    /** Returns why the [word] can not be a word of a wordlist, or `null` if it can. */
    fun wordErrorOrNull(word: String): WordlistWordError? = when {
        word.isBlank() -> WordlistWordError.BLANK
        word.length > MAX_WORD_LENGTH -> WordlistWordError.TOO_LONG
        '\n' in word || '\r' in word -> WordlistWordError.LINE_BREAK
        else -> null
    }

    /**
     * Returns why a wordlist can not remove the words [remove] and add the words [add],
     * or `null` if it can. The [existing] words are the ones of [add] that the wordlist
     * holds already. A word that is removed and added back counts as new, so replacing
     * a word with itself is fine.
     */
    fun patchErrorOrNull(
        existing: Set<String>,
        remove: Set<String>,
        add: List<String>,
    ): WordlistWordError? {
        val invalid = add.firstNotNullOfOrNull(::wordErrorOrNull)
        val words = add.distinct()
        val hasNew = words.any { it !in existing || it in remove }
        return when {
            remove.isEmpty() && add.isEmpty() -> WordlistWordError.BLANK
            invalid != null -> invalid
            words.isEmpty() || hasNew -> null
            words.size == 1 -> WordlistWordError.EXISTS
            else -> WordlistWordError.NO_NEW_WORDS
        }
    }
}
