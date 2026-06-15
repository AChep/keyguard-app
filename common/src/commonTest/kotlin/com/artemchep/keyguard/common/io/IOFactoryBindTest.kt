package com.artemchep.keyguard.common.io

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class IOFactoryBindTest {
    @Test
    fun `io returns provided value`() = runTest {
        assertEquals(123, io(123).bind())
    }

    @Test
    fun `ioRaise throws the same throwable instance`() = runTest {
        val e = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            ioRaise<Int>(e).bind()
        }
        assertSame(e, thrown)
    }

    @Test
    fun `ioUnit returns Unit`() = runTest {
        assertEquals(Unit, ioUnit().bind())
    }

    @Test
    fun `ioEffect executes lazily on bind`() = runTest {
        var executions = 0
        val io = ioEffect {
            executions += 1
            1
        }

        assertEquals(0, executions)
        assertEquals(1, io.bind())
        assertEquals(1, executions)
    }

    @Test
    fun `ioEffect context runs block and returns value`() = runTest {
        var executed = false
        val value = ioEffect(context = CoroutineName("io-effect-test")) {
            executed = true
            5
        }.bind()
        assertEquals(5, value)
        assertEquals(true, executed)
    }

    @Test
    fun `bind returns invoke result`() = runTest {
        val io: IO<Int> = { 42 }
        assertEquals(42, io.bind())
    }

    @Test
    fun `bindBlocking executes IO and returns result`() {
        assertEquals(7, io(7).bindBlocking())
    }

    @Test
    fun `List bind executes all and preserves ordering`() = runTest {
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
        ).bind()

        assertEquals(listOf(1, 2, 3), values)
    }

    @Test
    fun `lift transforms suspend function through IO pipeline`() = runTest {
        suspend fun increment(a: Int): Int = a + 1
        val lifted = (::increment).lift { it.map { value -> value * 2 } }
        assertEquals(6, lifted(2))
    }
}
