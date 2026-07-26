package com.artemchep.keyguard.feature.home.vault.screen

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparatorHolderPersistenceTest {
    @Test
    fun `serializes to a homogeneous string map`() {
        val holder = ComparatorHolder(
            comparator = AlphabeticalSort,
            reversed = true,
            favourites = false,
        )

        // The declared type matters as much as the contents: a Map<String, Any?> is neither
        // bundle-safe nor JSON-safe.
        val persisted: Map<String, String> = holder.toMap()

        assertEquals(
            mapOf(
                "comparator" to AlphabeticalSort.id,
                "reversed" to "true",
                "favourites" to "false",
            ),
            persisted,
        )
    }

    @Test
    fun `round trips through the persisted form`() {
        val holder = ComparatorHolder(
            comparator = AlphabeticalSort,
            reversed = true,
            favourites = true,
        )

        assertEquals(holder, ComparatorHolder.of(holder.toMap()))
    }

    @Test
    fun `restores a state written before the flags became strings`() {
        val holder = ComparatorHolder.of(
            mapOf<String, Any?>(
                "comparator" to AlphabeticalSort.id,
                "reversed" to true,
                "favourites" to false,
            ),
        )

        assertEquals(AlphabeticalSort, holder.comparator)
        assertTrue(holder.reversed)
        assertFalse(holder.favourites)
    }
}
