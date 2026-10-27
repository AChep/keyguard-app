package com.artemchep.keyguard.feature.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagsFieldLogicTest {
    private val catalog = mapOf(
        "work" to 12,
        "workout" to 3,
        "finance" to 7,
        "home-work" to 2,
        "network" to 5,
        "Shopping" to 4,
    )

    @Test
    fun textWithoutSeparatorsStaysInTheDraft() {
        assertEquals(
            TagInput(tags = emptyList(), rest = "work"),
            splitTagInput("work"),
        )
    }

    @Test
    fun separatorsFinishTags() {
        assertEquals(
            TagInput(tags = listOf("a", "b"), rest = "c"),
            splitTagInput("a, b; c"),
        )
        assertEquals(
            TagInput(tags = listOf("a", "b"), rest = ""),
            splitTagInput("a\r\nb\n"),
        )
        assertEquals(
            TagInput(tags = emptyList(), rest = ""),
            splitTagInput(" ,;"),
        )
    }

    @Test
    fun splitTagsKeepsTheLastSegment() {
        assertEquals(listOf("a", "b c"), splitTags(" a ;, b c "))
        assertEquals(emptyList(), splitTags("   "))
    }

    @Test
    fun addTagsTrimsAndSkipsDuplicatesIgnoringCase() {
        assertEquals(
            listOf("work", "new"),
            addTags(
                current = listOf("work"),
                input = listOf(" WORK ", "", "new", "New"),
                catalog = emptyMap(),
            ),
        )
    }

    @Test
    fun addTagsUsesTheCatalogSpelling() {
        assertEquals(
            listOf("work", "Shopping"),
            addTags(
                current = emptyList(),
                input = listOf("Work", "shopping"),
                catalog = catalog,
            ),
        )
    }

    @Test
    fun canonicalTagPrefersTheMostUsedSpelling() {
        val catalog = mapOf("Work" to 1, "WORK" to 9)
        assertEquals("WORK", canonicalTag("work", catalog))
        assertEquals("Work", canonicalTag("Work", catalog))
        assertEquals("other", canonicalTag("other", catalog))
    }

    @Test
    fun suggestionsAreRankedByTierThenUsage() {
        val names = rankTagSuggestions(
            query = "work",
            selected = emptyList(),
            catalog = catalog,
        ).map { it.name }
        // exact, prefix, word start, then substring
        assertEquals(listOf("work", "workout", "home-work", "network"), names)
    }

    @Test
    fun suggestionsHighlightTheMatch() {
        val suggestion = rankTagSuggestions(
            query = "work",
            selected = emptyList(),
            catalog = catalog,
        ).first { it.name == "home-work" }
        assertEquals(5..8, suggestion.match)
    }

    @Test
    fun emptyQuerySuggestsTheMostUsedTags() {
        val suggestions = rankTagSuggestions(
            query = " ",
            selected = listOf("WORK"),
            catalog = catalog,
            limit = 3,
        )
        assertEquals(listOf("finance", "network", "Shopping"), suggestions.map { it.name })
        assertNull(suggestions.first().match)
    }

    @Test
    fun boostedTagsComeFirstWithinATier() {
        val names = rankTagSuggestions(
            query = "",
            selected = emptyList(),
            catalog = catalog,
            boosted = listOf("HOME-WORK"),
            limit = 2,
        ).map { it.name }
        assertEquals(listOf("home-work", "work"), names)
    }

    @Test
    fun completionUsesTheFirstPrefixMatch() {
        val suggestions = rankTagSuggestions(
            query = "fin",
            selected = emptyList(),
            catalog = catalog,
        )
        assertEquals("ance", tagCompletion("fin", suggestions))
        assertEquals("ance", tagCompletion("FIN", suggestions))
        assertNull(tagCompletion("", suggestions))
        assertNull(tagCompletion("finance", suggestions))
    }
}
