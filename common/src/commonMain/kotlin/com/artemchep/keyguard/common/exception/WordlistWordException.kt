package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.common.model.WordlistWordError

/**
 * Thrown when a change to the words of a wordlist is refused.
 */
class WordlistWordException(
    val error: WordlistWordError,
) : IllegalArgumentException("Invalid wordlist words: $error"), NoAnalytics
