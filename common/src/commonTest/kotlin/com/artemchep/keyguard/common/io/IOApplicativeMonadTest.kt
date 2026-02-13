package com.artemchep.keyguard.common.io

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class IOApplicativeMonadTest {
    @Test
    fun `map transforms success value`() = runTest {
        assertEquals(8, io(4).map { it * 2 }.bind())
    }

    @Test
    fun `effectMap transforms success value`() = runTest {
        assertEquals(9, io(4).effectMap { it + 5 }.bind())
    }

    @Test
    fun `underscore effectMap transforms success value`() = runTest {
        assertEquals(10, io(4)._effectMap { it + 6 })
    }

    @Test
    fun `effectMap context transforms success value`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(
            11,
            io(4).effectMap(context = dispatcher) { it + 7 }.bind(),
        )
    }

    @Test
    fun `flatMap chains IOs`() = runTest {
        assertEquals(12, io(4).flatMap { io(it * 3) }.bind())
    }

    @Test
    fun `flatten unwraps nested IO`() = runTest {
        assertEquals(13, io(io(13)).flatten().bind())
    }

    @Test
    fun `flattenMap unwraps Either right`() = runTest {
        assertEquals(14, io(14.right()).flattenMap().bind())
    }

    @Test
    fun `flattenMap throws from Either left`() = runTest {
        val e = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            io(e.left()).flattenMap().bind()
        }
        assertSame(e, thrown)
    }

    @Test
    fun `fold maps success to ifRight`() = runTest {
        val result = io(15).fold(
            ifLeft = { -1 },
            ifRight = { it + 1 },
        )
        assertEquals(16, result)
    }

    @Test
    fun `fold maps non-fatal failure to ifLeft`() = runTest {
        val e = IllegalStateException("boom")
        val result = ioRaise<Int>(e).fold(
            ifLeft = { 17 },
            ifRight = { -1 },
        )
        assertEquals(17, result)
    }

    @Test
    fun `effectTap keeps original value when tap succeeds`() = runTest {
        var tapCalled = false
        val value = io(18).effectTap {
            tapCalled = true
        }.bind()
        assertTrue(tapCalled)
        assertEquals(18, value)
    }

    @Test
    fun `effectTap suppresses non-fatal tap failure and keeps original value`() = runTest {
        val value = io(19).effectTap {
            throw IllegalStateException("tap failure")
        }.bind()
        assertEquals(19, value)
    }

    @Test
    fun `effectTap rethrows cancellation from tap`() = runTest {
        assertFailsWith<CancellationException> {
            io(20).effectTap {
                throw CancellationException("cancel")
            }.bind()
        }
    }

    @Test
    fun `flatTap keeps original value on tap success`() = runTest {
        val value = io(21).flatTap {
            ioUnit()
        }.bind()
        assertEquals(21, value)
    }

    @Test
    fun `flatTap suppresses non-fatal tap failure`() = runTest {
        val value = io(22).flatTap {
            ioRaise(IllegalStateException("tap failure"))
        }.bind()
        assertEquals(22, value)
    }

    @Test
    fun `biEffectTap executes success branch on success`() = runTest {
        var success = false
        val value = io(23).biEffectTap(
            ifException = { fail("must not call exception branch") },
            ifSuccess = { success = true },
        ).bind()
        assertTrue(success)
        assertEquals(23, value)
    }

    @Test
    fun `biEffectTap executes failure branch on failure`() = runTest {
        var failure = false
        val e = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            ioRaise<Int>(e).biEffectTap(
                ifException = {
                    failure = true
                    assertSame(e, it)
                },
                ifSuccess = { fail("must not call success branch") },
            ).bind()
        }
        assertTrue(failure)
        assertSame(e, thrown)
    }

    @Test
    fun `biFlatTap executes failure branch and keeps original failure`() = runTest {
        var failure = false
        val e = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            ioRaise<Int>(e).biFlatTap(
                ifException = {
                    failure = true
                    ioRaise(IllegalArgumentException("secondary failure"))
                },
                ifSuccess = { fail("must not call success branch") },
            ).bind()
        }
        assertTrue(failure)
        assertSame(e, thrown)
    }

    @Test
    fun `biFlatTap executes success branch and keeps original success`() = runTest {
        var success = false
        val value = io(24).biFlatTap(
            ifException = { fail("must not call exception branch") },
            ifSuccess = {
                success = true
                io(999)
            },
        ).bind()
        assertTrue(success)
        assertEquals(24, value)
    }
}
