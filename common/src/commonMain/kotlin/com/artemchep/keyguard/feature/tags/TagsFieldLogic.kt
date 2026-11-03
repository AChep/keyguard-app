package com.artemchep.keyguard.feature.tags

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase

/**
 * Characters that finish a tag while typing or pasting. KeePass
 * splits its tags string on `,` and `;`, so neither can be a part
 * of a tag anyway.
 */
private val TAG_SEPARATORS = charArrayOf(',', ';', '\n', '\r')

private const val DEFAULT_SUGGESTION_LIMIT = 8

data class TagInput(
    /** Finished, trimmed and non-blank tags. */
    val tags: List<String>,
    /** The text after the last separator. */
    val rest: String,
)

/**
 * Splits [text] into finished tags and the text that is still
 * being typed: "a, b; c" gives tags `a`, `b` and the rest `c`.
 */
fun splitTagInput(text: String): TagInput {
    if (text.none { it in TAG_SEPARATORS }) {
        return TagInput(
            tags = emptyList(),
            rest = text,
        )
    }

    val parts = text.split(*TAG_SEPARATORS)
    return TagInput(
        tags = parts
            .dropLast(1)
            .map(String::trim)
            .filter(String::isNotEmpty),
        rest = parts.last().trimStart(),
    )
}

/**
 * Splits [text] into tags, treating the text after the last
 * separator as a finished tag too.
 */
fun splitTags(text: String): List<String> = text
    .split(*TAG_SEPARATORS)
    .map(String::trim)
    .filter(String::isNotEmpty)

/**
 * Appends [input] to [current], skipping blanks and tags that are
 * already there, ignoring case. A tag that exists in the [catalog]
 * takes the catalog's spelling, so typing "Work" adds the existing
 * "work" instead of a second tag.
 */
fun addTags(
    current: List<String>,
    input: List<String>,
    catalog: Map<String, Int>,
): List<String> {
    val out = current.toMutableList()
    val seen = current
        .mapTo(HashSet()) { it.lowercase() }
    input.forEach { raw ->
        val tag = raw.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { canonicalTag(it, catalog) }
            ?: return@forEach
        if (seen.add(tag.lowercase())) {
            out += tag
        }
    }
    return out
}

/**
 * Returns the catalog's spelling of the [tag], or the [tag]
 * itself if the catalog does not have it. If several spellings
 * exist, the most used one wins.
 */
fun canonicalTag(
    tag: String,
    catalog: Map<String, Int>,
): String {
    if (tag in catalog) {
        return tag
    }
    return catalog.entries
        .filter { it.key.equals(tag, ignoreCase = true) }
        .maxByOrNull { it.value }
        ?.key
        ?: tag
}

data class TagSuggestion(
    val name: String,
    /** The number of items that have the tag. */
    val usage: Int,
    /** The part of the [name] that matches the query. */
    val match: IntRange? = null,
)

/**
 * Ranks the [catalog] tags against the [query]. Exact matches come
 * first, then prefix matches, then matches at the start of a word, then
 * any other matches. Within a tier, [boosted] tags come first, then the
 * most used ones. An empty query returns the most used tags.
 *
 * Tags that are already [selected] are never suggested.
 */
fun rankTagSuggestions(
    query: String,
    selected: Collection<String>,
    catalog: Map<String, Int>,
    boosted: Collection<String> = emptyList(),
    limit: Int = DEFAULT_SUGGESTION_LIMIT,
): List<TagSuggestion> {
    val q = query.trim()
    val selectedKeys = selected
        .mapTo(HashSet()) { it.lowercase() }
    val boostedKeys = boosted
        .mapTo(HashSet()) { it.lowercase() }

    class Candidate(
        val suggestion: TagSuggestion,
        val tier: Int,
        val boosted: Boolean,
    )

    val comparator = compareBy<Candidate> { it.tier }
        .thenByDescending { it.boosted }
        .thenByDescending { it.suggestion.usage }
        .then(StringComparatorIgnoreCase { it.suggestion.name })
    return catalog.entries
        .asSequence()
        .filter { it.key.lowercase() !in selectedKeys }
        .mapNotNull { (name, usage) ->
            val (tier, start) = matchTag(name, q)
                ?: return@mapNotNull null
            val match = if (q.isNotEmpty()) {
                start until start + q.length
            } else {
                null
            }
            Candidate(
                suggestion = TagSuggestion(
                    name = name,
                    usage = usage,
                    match = match,
                ),
                tier = tier,
                boosted = name.lowercase() in boostedKeys,
            )
        }
        .sortedWith(comparator)
        .take(limit)
        .map { it.suggestion }
        .toList()
}

private const val TIER_EXACT = 0
private const val TIER_PREFIX = 1
private const val TIER_WORD = 2
private const val TIER_ANY = 3

/**
 * Returns the match tier and the index of the match, or
 * `null` if the [name] does not contain the [query].
 */
private fun matchTag(
    name: String,
    query: String,
): Pair<Int, Int>? = when {
    query.isEmpty() || name.equals(query, ignoreCase = true) -> TIER_EXACT to 0
    name.startsWith(query, ignoreCase = true) -> TIER_PREFIX to 0
    else -> matchTagInside(name, query)
}

private fun matchTagInside(
    name: String,
    query: String,
): Pair<Int, Int>? {
    fun indexAfter(index: Int) = name
        .indexOf(query, startIndex = index + 1, ignoreCase = true)
        .takeIf { it > 0 }

    val indices = generateSequence(indexAfter(0), ::indexAfter)
    val first = indices.firstOrNull()
        ?: return null
    val wordStart = indices.firstOrNull { !name[it - 1].isLetterOrDigit() }
    return if (wordStart != null) {
        TIER_WORD to wordStart
    } else {
        TIER_ANY to first
    }
}

/**
 * Returns the text that completes the [query] to the first
 * suggestion that starts with it, or `null` if there is none.
 */
fun tagCompletion(
    query: String,
    suggestions: List<TagSuggestion>,
): String? {
    if (query.isEmpty()) {
        return null
    }
    return suggestions
        .firstOrNull { suggestion ->
            suggestion.name.length > query.length &&
                suggestion.name.startsWith(query, ignoreCase = true)
        }
        ?.name
        ?.substring(query.length)
}

/**
 * Counts how many items have each tag. Items in
 * the trash are not counted.
 */
fun tagUsage(ciphers: List<DSecret>): Map<String, Int> {
    val out = HashMap<String, Int>()
    ciphers.forEach { cipher ->
        if (cipher.deleted) {
            return@forEach
        }
        cipher.tags.forEach { tag ->
            out[tag] = (out[tag] ?: 0) + 1
        }
    }
    return out
}
