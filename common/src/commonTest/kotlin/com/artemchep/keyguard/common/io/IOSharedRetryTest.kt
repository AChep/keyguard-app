package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IOSharedRetryTest {
    @Test
    fun `shared caches successful result across multiple binds`() = runTest {
        val invocations = AtomicInteger(0)
        val shared = ioEffect {
            invocations.incrementAndGet()
            1
        }.shared(tag = "IOSharedRetryTest-success-cache")

        val first = shared.bind()
        val second = shared.bind()
        val third = shared.bind()

        assertEquals(1, first)
        assertEquals(1, second)
        assertEquals(1, third)
        assertEquals(1, invocations.get())
    }

    @Test
    fun `shared caches failure when retry flag is false`() = runTest {
        val invocations = AtomicInteger(0)
        val shared = ioEffect<Int> {
            invocations.incrementAndGet()
            throw IllegalStateException("boom")
        }.shared(
            tag = "IOSharedRetryTest-failure-cache",
            ifFailedRetryOnNextBind = false,
        )

        repeat(2) {
            assertFailsWith<IllegalStateException> {
                shared.bind()
            }
        }

        assertEquals(1, invocations.get())
    }

    @Test
    fun `shared retries after failure when retry flag is true`() = runTest {
        val invocations = AtomicInteger(0)
        val shared = ioEffect {
            val call = invocations.incrementAndGet()
            if (call == 1) {
                throw IllegalStateException("first failure")
            }
            2
        }.shared(
            tag = "IOSharedRetryTest-retry-enabled",
            ifFailedRetryOnNextBind = true,
        )

        assertFailsWith<IllegalStateException> {
            shared.bind()
        }
        assertEquals(2, shared.bind())
        assertEquals(2, invocations.get())
    }

    @Test
    fun `shared deduplicates concurrent callers on first execution`() = runTest {
        val invocations = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val shared = ioEffect {
            invocations.incrementAndGet()
            gate.await()
            3
        }.shared(tag = "IOSharedRetryTest-concurrent-success")

        val deferreds = List(8) {
            async {
                shared.bind()
            }
        }
        gate.complete(Unit)

        val values = deferreds.awaitAll()
        assertEquals(List(8) { 3 }, values)
        assertEquals(1, invocations.get())
    }

    @Test
    fun `shared allows retry after failed first wave with retry flag true`() = runTest {
        val invocations = AtomicInteger(0)
        val firstWaveStarted = CompletableDeferred<Unit>()
        val secondWaveStarted = CompletableDeferred<Unit>()
        val firstWaveGate = CompletableDeferred<Unit>()
        val secondWaveGate = CompletableDeferred<Unit>()

        val shared = ioEffect {
            when (invocations.incrementAndGet()) {
                1 -> {
                    firstWaveStarted.complete(Unit)
                    firstWaveGate.await()
                    throw IllegalStateException("first wave")
                }

                else -> {
                    secondWaveStarted.complete(Unit)
                    secondWaveGate.await()
                    4
                }
            }
        }.shared(
            tag = "IOSharedRetryTest-wave-retry",
            ifFailedRetryOnNextBind = true,
        )

        val firstWave = List(5) {
            async {
                runCatching { shared.bind() }
            }
        }
        firstWaveStarted.await()
        firstWaveGate.complete(Unit)

        val firstResults = firstWave.awaitAll()
        assertTrue(firstResults.all { it.isFailure })
        firstResults.forEach { result ->
            assertIs<IllegalStateException>(result.exceptionOrNull())
        }
        assertEquals(1, invocations.get())

        val secondWave = List(5) {
            async {
                runCatching { shared.bind() }
            }
        }
        secondWaveStarted.await()
        secondWaveGate.complete(Unit)

        val secondResults = secondWave.awaitAll()
        assertTrue(secondResults.all { it.isSuccess })
        assertEquals(List(5) { 4 }, secondResults.map { it.getOrThrow() })
        assertEquals(2, invocations.get())
    }
}
