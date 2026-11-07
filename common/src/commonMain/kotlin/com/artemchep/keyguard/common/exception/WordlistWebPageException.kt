package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/**
 * Thrown when a wordlist URL responds with a web page instead of a
 * plain text file, for example a repository's file view instead of
 * the raw file.
 */
class WordlistWebPageException : IllegalStateException(
    "The URL returned a web page, not a wordlist. Use a link to the raw file.",
), Readable, NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_wordlist_url_returned_web_page)
}
