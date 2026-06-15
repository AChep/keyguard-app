package com.artemchep.keyguard.common.io

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IOCombineExtrasTest {
    @Test
    fun `combineSeq executes all IOs sequentially and returns ordered results`() = runTest {
        val values = listOf(
            ioEffect {
                delay(30L)
                1
            },
            ioEffect {
                delay(10L)
                2
            },
            ioEffect {
                delay(20L)
                3
            },
        ).combineSeq().bind()

        assertEquals(listOf(1, 2, 3), values)
    }

    @Test
    fun `combineIo combines two successful IOs`() = runTest {
        val result = combineIo(
            a = io(2),
            b = io(3),
            combine = { a, b -> a + b },
        ).bind()

        assertEquals(5, result)
    }

    @Test
    fun `combineIo propagates failure from first IO`() = runTest {
        assertFailsWith<IllegalStateException> {
            combineIo<Int, Int, Int>(
                a = ioRaise<Int>(IllegalStateException("first")),
                b = io(3),
                combine = { a, b -> a + b },
            ).bind()
        }
    }

    @Test
    fun `combineIo propagates failure from second IO`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            combineIo<Int, Int, Int>(
                a = io(2),
                b = ioRaise<Int>(IllegalArgumentException("second")),
                combine = { a, b -> a + b },
            ).bind()
        }
    }

    @Test
    fun `combine handles empty list with non-zero bucket`() = runTest {
        val values = emptyList<IO<Int>>().combine(bucket = 1).bind()
        assertEquals(emptyList(), values)
    }
}
