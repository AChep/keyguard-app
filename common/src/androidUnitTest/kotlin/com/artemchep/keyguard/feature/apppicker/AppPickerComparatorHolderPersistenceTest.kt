package com.artemchep.keyguard.feature.apppicker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppPickerComparatorHolderPersistenceTest {
    @Test
    fun `serializes to a homogeneous string map`() {
        val holder = AppPickerComparatorHolder(
            comparator = AppPickerInstallTimeSort,
            reversed = true,
        )

        // The declared type matters as much as the contents: a Map<String, Any?> is neither
        // bundle-safe nor JSON-safe.
        val persisted: Map<String, String> = holder.toMap()

        assertEquals(
            mapOf(
                "comparator" to AppPickerInstallTimeSort.id,
                "reversed" to "true",
            ),
            persisted,
        )
    }

    @Test
    fun `round trips through the persisted form`() {
        val holder = AppPickerComparatorHolder(
            comparator = AppPickerInstallTimeSort,
            reversed = true,
        )

        assertEquals(holder, AppPickerComparatorHolder.of(holder.toMap()))
    }

    @Test
    fun `restores a state written before the flag became a string`() {
        val holder = AppPickerComparatorHolder.of(
            mapOf<String, Any?>(
                "comparator" to AppPickerInstallTimeSort.id,
                "reversed" to true,
            ),
        )

        assertEquals(AppPickerInstallTimeSort, holder.comparator)
        assertTrue(holder.reversed)
    }
}
