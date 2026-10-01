package com.artemchep.keyguard.apple.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListNavigationOriginTest {
    @Test
    fun `root selection replaces the whole detail branch`() {
        val path = listOf(1L, 2L)
        val start = requireNotNull(listDetailStartIndex(path, null))
        assertEquals(emptyList(), path.take(start))
        assertEquals(path, path.drop(start))
    }

    @Test
    fun `nested selection preserves parent history and owning list`() {
        val path = listOf(1L, 2L, 3L, 4L)
        val start = requireNotNull(listDetailStartIndex(path, 2L))
        assertEquals(listOf(1L, 2L), path.take(start))
        assertEquals(listOf(3L, 4L), path.drop(start))
    }

    @Test
    fun `delayed open after popping the list cannot replace another branch`() {
        assertNull(listDetailStartIndex(listOf(1L), 2L))
        assertNull(listDetailStartIndex(emptyList(), 2L))
    }

    @Test
    fun `newly opened list has an empty detail branch`() {
        assertEquals(2, listDetailStartIndex(listOf(1L, 2L), 2L))
    }
}
