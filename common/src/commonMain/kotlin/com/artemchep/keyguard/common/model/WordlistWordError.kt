package com.artemchep.keyguard.common.model

/**
 * Why a change to the words of a wordlist is refused,
 * see [com.artemchep.keyguard.common.usecase.impl.ReadWordlistUtil.patchErrorOrNull].
 */
enum class WordlistWordError {
    /** A word is blank, or the change has no words at all. */
    BLANK,

    /** A word is longer than [com.artemchep.keyguard.common.usecase.impl.ReadWordlistUtil.MAX_WORD_LENGTH]. */
    TOO_LONG,

    /** A word holds a line break. */
    LINE_BREAK,

    /** The only added word is in the wordlist already. */
    EXISTS,

    /** Every added word is in the wordlist already. */
    NO_NEW_WORDS,
}
