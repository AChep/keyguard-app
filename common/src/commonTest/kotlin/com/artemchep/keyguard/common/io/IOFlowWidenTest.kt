package com.artemchep.keyguard.common.io

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IOFlowWidenTest {
    @Test
    fun `toIO returns first emitted value`() = runTest {
        val value = flowOf(1, 2, 3).toIO().bind()
        assertEquals(1, value)
    }

    @Test
    fun `toIO propagates upstream error`() = runTest {
        val e = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            flow<Int> {
                throw e
            }.toIO().bind()
        }
        assertEquals(e.message, thrown.message)
    }

    @Test
    fun `nullable widens flow preserving values`() = runTest {
        val values = flowOf(4, 5, 6).nullable().toList()
        assertEquals(listOf(4, 5, 6), values)
    }
}
