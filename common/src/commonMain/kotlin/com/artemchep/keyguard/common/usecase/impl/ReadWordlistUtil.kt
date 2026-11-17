package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.WordlistWordError
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

object ReadWordlistUtil {
    /** The longest word of a wordlist. */
    const val MAX_WORD_LENGTH: Int = 512

    private const val BYTE_ORDER_MARK = "\uFEFF"

    /**
     * Parses the lines of a wordlist file. Skips empty and
     * commented lines; every other line is a word.
     *
     * If most of the lines start with dice numbers, such as
     * `11111<TAB>abacus`, then the file is a Diceware list:
     * only the words after the numbers are kept, and lines
     * without numbers, such as a PGP signature, are skipped.
     */
    fun Sequence<String>.parseAsWordlist(): ImmutableList<String> {
        val lines = this
            .mapIndexed { index, line ->
                if (index == 0) line.removePrefix(BYTE_ORDER_MARK) else line
            }
            .filter(::isWordLine)
            .toList()
        val dicewareWords = lines.mapNotNull(::dicewareWordOrNull)
        val isDiceware = dicewareWords.size * 2 > lines.size
        return (if (isDiceware) dicewareWords else lines).toImmutableList()
    }

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

    private fun isWordLine(line: String) = line.isNotBlank() && line[0] !in "#;-/"

    /**
     * Returns the word of a Diceware line: one or more dice
     * numbers, then spaces or tabs, then the word. Returns `null`
     * if the line has another format.
     */
    private fun dicewareWordOrNull(line: String): String? {
        val numbersEnd = line.indexOfFirst { it !in '1'..'6' }
        val hasNumbersAndSeparator = numbersEnd > 0 && line[numbersEnd] in " \t"
        if (!hasNumbersAndSeparator) {
            return null
        }
        return line
            .substring(numbersEnd)
            .trim()
            .takeIf { it.isNotEmpty() }
    }
}
