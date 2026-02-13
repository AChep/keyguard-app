package com.artemchep.keyguard.common.io

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IOErrorHandlingTest {
    @Test
    fun `IO attempt returns Right on success`() = runTest {
        val result = io(1).attempt().bind()
        val right = assertIs<Either.Right<Int>>(result)
        assertEquals(1, right.value)
    }

    @Test
    fun `IO attempt returns Left on non-fatal failure`() = runTest {
        val e = IllegalStateException("boom")
        val result = ioRaise<Int>(e).attempt().bind()
        val left = assertIs<Either.Left<Throwable>>(result)
        assertSame(e, left.value)
    }

    @Test
    fun `retry retries until predicate returns false and then throws original`() = runTest {
        val e = IllegalStateException("boom")
        var attempts = 0
        val thrown = assertFailsWith<IllegalStateException> {
            ioEffect<Int> {
                attempts += 1
                throw e
            }.retry { _, index ->
                index < 2
            }.bind()
        }
        assertSame(e, thrown)
        assertEquals(3, attempts)
    }

    @Test
    fun `retry eventually succeeds after transient failures`() = runTest {
        var attempts = 0
        val result = ioEffect {
            attempts += 1
            if (attempts < 3) {
                throw IllegalStateException("transient")
            }
            99
        }.retry { _, _ -> true }
            .bind()
        assertEquals(99, result)
        assertEquals(3, attempts)
    }

    @Test
    fun `retry does not swallow cancellation`() = runTest {
        assertFailsWith<CancellationException> {
            ioEffect<Int> {
                throw CancellationException("cancel")
            }.retry { _, _ ->
                true
            }.bind()
        }
    }

    @Test
    fun `Either toIO converts Right to success`() = runTest {
        assertEquals(2, 2.right().toIO().bind())
    }

    @Test
    fun `Either toIO converts Left to throw`() = runTest {
        val e = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            e.left().toIO<Int>().bind()
        }
        assertSame(e, thrown)
    }

    @Test
    fun `finally executes on success`() = runTest {
        var finalizerCalled = false
        val value = io(3).finally {
            finalizerCalled = true
        }.bind()
        assertEquals(3, value)
        assertTrue(finalizerCalled)
    }

    @Test
    fun `finally executes on failure`() = runTest {
        var finalizerCalled = false
        assertFailsWith<IllegalStateException> {
            ioRaise<Int>(IllegalStateException("boom")).finally {
                finalizerCalled = true
            }.bind()
        }
        assertTrue(finalizerCalled)
    }

    @Test
    fun `handleErrorWith recovers when predicate true`() = runTest {
        val value = ioRaise<Int>(IllegalStateException("boom"))
            .handleErrorWith(
                predicate = { it is IllegalStateException },
                block = { io(7) },
            )
            .bind()
        assertEquals(7, value)
    }

    @Test
    fun `handleErrorWith does not recover when predicate false`() = runTest {
        val e = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            ioRaise<Int>(e)
                .handleErrorWith(
                    predicate = { false },
                    block = { io(7) },
                )
                .bind()
        }
        assertSame(e, thrown)
    }

    @Test
    fun `handleError recovers with fallback value`() = runTest {
        val value = ioRaise<Int>(IllegalStateException("boom"))
            .handleError { 8 }
            .bind()
        assertEquals(8, value)
    }

    @Test
    fun `handleErrorTap executes side-effect then rethrows original`() = runTest {
        var tapped: Throwable? = null
        val e = IllegalStateException("boom")
        val thrown = assertFailsWith<IllegalStateException> {
            ioRaise<Int>(e).handleErrorTap {
                tapped = it
            }.bind()
        }
        assertSame(e, tapped)
        assertSame(e, thrown)
    }

    @Test
    fun `throwIfFatalOrCancellation rethrows CancellationException`() {
        assertFailsWith<CancellationException> {
            CancellationException("cancel").throwIfFatalOrCancellation()
        }
    }

    @Test
    fun `throwIfFatalOrCancellation rethrows Error`() {
        assertFailsWith<AssertionError> {
            AssertionError("fatal").throwIfFatalOrCancellation()
        }
    }

    @Test
    fun `throwIfFatalOrCancellation returns for ordinary Exception`() {
        val result = runCatching {
            IllegalStateException("ok").throwIfFatalOrCancellation()
        }
        assertTrue(result.isSuccess)
    }
}
