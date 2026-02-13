package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
