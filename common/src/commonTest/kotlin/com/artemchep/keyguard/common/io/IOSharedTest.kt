package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IOSharedTest {
    @Test
    fun `shared computes once for concurrent successful callers`() = runTest {
        val invocations = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val sharedIo = ioEffect {
            invocations.incrementAndGet()
            gate.await()
            7
        }.shared(tag = "IOSharedTest-memoization")

        val deferreds = List(10) {
            async {
                sharedIo.bind()
            }
        }
        gate.complete(Unit)

        val values = deferreds.awaitAll()
        assertEquals(List(10) { 7 }, values)
        assertEquals(1, invocations.get())
    }

    @Test
    fun `shared publishes cached success to concurrent dispatcher threads`() = runTest {
        val invocations = AtomicInteger(0)
        val sharedIo = ioEffect {
            invocations.incrementAndGet()
            7
        }.shared(tag = "IOSharedTest-concurrent-cached-success")
        assertEquals(7, sharedIo.bind())

        val sums = List(CONCURRENT_READER_COUNT) {
            async(Dispatchers.Default) {
                var sum = 0
                repeat(READS_PER_READER) {
                    sum += sharedIo.bind()
                }
                sum
            }
        }.awaitAll()

        assertEquals(
            List(CONCURRENT_READER_COUNT) { 7 * READS_PER_READER },
            sums,
        )
        assertEquals(1, invocations.get())
    }

    @Test
    fun `shared publishes cached failure to concurrent dispatcher threads`() = runTest {
        val invocations = AtomicInteger(0)
        val sharedIo = ioEffect<Int> {
            invocations.incrementAndGet()
            throw IllegalStateException("boom")
        }.shared(tag = "IOSharedTest-concurrent-cached-failure")
        assertFailsWith<IllegalStateException> {
            sharedIo.bind()
        }

        val failures = List(CONCURRENT_READER_COUNT) {
            async(Dispatchers.Default) {
                repeat(READS_PER_READER) {
                    val result = runCatching { sharedIo.bind() }
                    check(result.exceptionOrNull() is IllegalStateException)
                }
            }
        }.awaitAll()

        assertEquals(List(CONCURRENT_READER_COUNT) { Unit }, failures)
        assertEquals(1, invocations.get())
    }

    private companion object {
        const val CONCURRENT_READER_COUNT = 16
        const val READS_PER_READER = 1_000
    }
}
