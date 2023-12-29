package com.artemchep.keyguard.feature.home.vault.search

import kotlin.math.sqrt

private const val ignoreLength = 3

private val ignoreWords = setOf(
    "www",
    // popular domains
    "com",
    "tk",
    "cn",
    "de",
    "net",
    "uk",
    "org",
    "nl",
    "icu",
    "ru",
    "eu",
    // rising domains
    "icu",
    "top",
    "xyz",
    "site",
    "online",
    "club",
    "wang",
    "vip",
    "shop",
    "work",
    // common words used in domain names
    "login",
    "signin",
    "auth",
    // top 100 english words that are > ignoreLength
    "there",
    "some",
    "than",
    "this",
    "would",
    "first",
    "have",
    "each",
    "make",
    "water",
    "from",
    "which",
    "like",
    "been",
    "call",
    "into",
    "time",
    "that",
    "their",
    "word",
    "look",
    "now",
    "will",
    "find",
    "more",
    "long",
    "what",
    "other",
    "write",
    "down",
    "about",
    "were",
    "many",
    "number",
    "with",
    "when",
    "then",
    "come",
    "your",
    "them",
    "made",
    "they",
    "these",
    "could",
    "said",
    "people",
    "part",
)

fun findAlike(
    source: Collection<String>,
    query: Collection<String>,
    ignoreCommonWords: Boolean = true,
): Float {
    val a = find(source, query, ignoreCommonWords = ignoreCommonWords)
    val b = find(query, source, ignoreCommonWords = ignoreCommonWords)
    return a + b
}

fun find(
    source: Collection<String>,
    query: Collection<String>,
    ignoreCommonWords: Boolean = true,
): Float {
    if (source.isEmpty() || query.isEmpty()) {
        return 0.0f
    }

    var score = 0f
    source.forEachIndexed { _, comp ->
        var max = 0f
        query.forEach { queryComp ->
            if (ignoreCommonWords && (queryComp.length <= ignoreLength || queryComp in ignoreWords) || queryComp.length <= 1) {
                return@forEach
            }
            // Find the query in the target text
            val i = comp.indexOf(queryComp)
            if (i == -1) {
                return@forEach
            }

            val queryPositionScore = if (i == 0) {
                1f
            } else {
                // We slightly discourage components that
                // not start from the query.
                0.65f
            }
            val queryTotalLengthScore = 10f / sqrt(queryComp.length.toFloat())
            val queryMatchLengthScore = queryComp.length.toFloat() / comp.length.toFloat()
            val s = 0f +
                    queryPositionScore +
                    queryTotalLengthScore * queryMatchLengthScore
            if (s > max) {
                max = s
            }
        }
        score += max
    }

    return score / source.size.toFloat()
}
