package com.artemchep.keyguard.android.autofill.v2.util

import com.artemchep.keyguard.android.autofill.v2.util.AhoCorasickAutomaton.MatchMode
import com.artemchep.keyguard.android.autofill.v2.util.KeywordMatcher.match

/**
 * Keyword-list tag constants — each is a single bit in a [Long] bitmask.
 *
 * Used with [KeywordMatcher.match] to identify which keyword lists
 * matched a given text blob in a single automaton pass.
 */
object KeywordTag {
    const val PASSWORD: Long = 1L shl 0
    const val PHONE: Long = 1L shl 1
    const val USERNAME: Long = 1L shl 2
    const val EMAIL: Long = 1L shl 3
    const val SEARCH: Long = 1L shl 4
    const val COMMENT: Long = 1L shl 5
    const val OTP: Long = 1L shl 6
    const val LOGIN_BUTTON: Long = 1L shl 7
    const val SIGNUP_BUTTON: Long = 1L shl 8
    const val RESET: Long = 1L shl 9
    const val NAME: Long = 1L shl 10
    const val GIVEN_NAME: Long = 1L shl 11
    const val FAMILY_NAME: Long = 1L shl 12
    const val ADDRESS: Long = 1L shl 13
    const val CITY: Long = 1L shl 14
    const val REGION: Long = 1L shl 15
    const val COUNTRY: Long = 1L shl 16
    const val POSTAL_CODE: Long = 1L shl 17
    const val CREDIT_CARD_NUMBER: Long = 1L shl 18
    const val CARD_SECURITY_CODE: Long = 1L shl 19
    const val CARD_EXPIRY: Long = 1L shl 20
    const val TEL_FALSE_POSITIVE: Long = 1L shl 21
    const val STRONG_USERNAME: Long = 1L shl 22
}

/**
 * Returns `true` if this bitmask contains the given [tag] bit(s).
 *
 * Usage: `val m = KeywordMatcher.match(blob); if (m has KeywordTag.EMAIL) { ... }`
 */
infix fun Long.has(tag: Long): Boolean = (this and tag) != 0L

/**
 * Singleton multi-pattern matcher backed by an [AhoCorasickAutomaton].
 *
 * All keyword lists from [FieldSignals][com.artemchep.keyguard.android.autofill.v2.util]
 * are registered in a single automaton at class-load time. Calling [match]
 * scans the text **once** and returns a [Long] bitmask indicating which
 * keyword lists had at least one match. Short Latin keywords require token
 * boundaries; longer and non-Latin keywords keep substring semantics.
 *
 * This replaces the pattern of calling `containsAny(blob, LIST)` N times
 * (one per keyword list, each scanning the full blob) with a single
 * O(text_length) pass regardless of the number of keyword lists.
 *
 * Usage:
 * ```
 * val m = KeywordMatcher.match(blob)
 * if (m has KeywordTag.EMAIL) { /* blob contains an EMAIL keyword */ }
 * if (m has KeywordTag.PASSWORD) { /* blob contains a PASSWORD keyword */ }
 * ```
 */
object KeywordMatcher {
    private val TOKEN_BOUNDARY_KEYWORDS = setOf("pass")

    private val automaton: AhoCorasickAutomaton = buildAutomaton()

    /**
     * Scans [text] through the Aho-Corasick automaton and returns a
     * bitmask of all [KeywordTag]s whose keyword lists had at least
     * one match.
     */
    fun match(text: String): Long = automaton.match(text)

    private fun buildAutomaton(): AhoCorasickAutomaton =
        AhoCorasickAutomaton
            .Builder()
            .addKeywords(PASSWORD_KEYWORDS, KeywordTag.PASSWORD)
            .addKeywords(PHONE_KEYWORDS, KeywordTag.PHONE)
            .addKeywords(USERNAME_KEYWORDS, KeywordTag.USERNAME)
            .addKeywords(EMAIL_KEYWORDS, KeywordTag.EMAIL)
            .addKeywords(SEARCH_KEYWORDS, KeywordTag.SEARCH)
            .addKeywords(COMMENT_KEYWORDS, KeywordTag.COMMENT)
            .addKeywords(OTP_KEYWORDS, KeywordTag.OTP)
            .addKeywords(LOGIN_BUTTON_KEYWORDS, KeywordTag.LOGIN_BUTTON)
            .addKeywords(SIGNUP_BUTTON_KEYWORDS, KeywordTag.SIGNUP_BUTTON)
            .addKeywords(RESET_KEYWORDS, KeywordTag.RESET)
            .addKeywords(NAME_KEYWORDS, KeywordTag.NAME)
            .addKeywords(GIVEN_NAME_KEYWORDS, KeywordTag.GIVEN_NAME)
            .addKeywords(FAMILY_NAME_KEYWORDS, KeywordTag.FAMILY_NAME)
            .addKeywords(ADDRESS_KEYWORDS, KeywordTag.ADDRESS)
            .addKeywords(CITY_KEYWORDS, KeywordTag.CITY)
            .addKeywords(REGION_KEYWORDS, KeywordTag.REGION)
            .addKeywords(COUNTRY_KEYWORDS, KeywordTag.COUNTRY)
            .addKeywords(POSTAL_CODE_KEYWORDS, KeywordTag.POSTAL_CODE)
            .addKeywords(CREDIT_CARD_NUMBER_KEYWORDS, KeywordTag.CREDIT_CARD_NUMBER)
            .addKeywords(CARD_SECURITY_CODE_KEYWORDS, KeywordTag.CARD_SECURITY_CODE)
            .addKeywords(CARD_EXPIRY_KEYWORDS, KeywordTag.CARD_EXPIRY)
            .addKeywords(TEL_FALSE_POSITIVE_KEYWORDS, KeywordTag.TEL_FALSE_POSITIVE)
            .addKeywords(STRONG_USERNAME_KEYWORDS, KeywordTag.STRONG_USERNAME)
            .build()

    private fun AhoCorasickAutomaton.Builder.addKeywords(
        keywords: List<String>,
        tag: Long,
    ): AhoCorasickAutomaton.Builder {
        keywords.forEach { keyword ->
            addPattern(
                pattern = keyword,
                tag = tag,
                matchMode = keyword.matchMode(),
            )
        }
        return this
    }

    private fun String.matchMode(): MatchMode =
        if (this in TOKEN_BOUNDARY_KEYWORDS || isShortAsciiKeyword()) {
            MatchMode.ASCII_TOKEN
        } else {
            MatchMode.SUBSTRING
        }

    private fun String.isShortAsciiKeyword(): Boolean =
        isNotEmpty() && length <= 3 && all { ch ->
            ch in 'a'..'z' ||
                    ch in '0'..'9'
        }
}
