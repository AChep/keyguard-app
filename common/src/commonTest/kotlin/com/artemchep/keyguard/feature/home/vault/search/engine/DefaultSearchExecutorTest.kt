package com.artemchep.keyguard.feature.home.vault.search.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultSearchExecutorTest {
    private val executor = DefaultSearchExecutor()

    @Test
    fun `maps sequential inputs preserving order`() = runTest {
        val values = listOf(1, 2, 3, 4)
        val out = executor.map(values) { it * 2 }
        assertEquals(listOf(2, 4, 6, 8), out)
    }

    @Test
    fun `maps large inputs preserving deterministic order`() = runTest {
        val values = (0 until 5000).toList()
        val out = executor.map(values) { it + 1 }
        assertEquals(values.size, out.size)
        assertEquals(1, out.first())
        assertEquals(5000, out.last())
    }
}
