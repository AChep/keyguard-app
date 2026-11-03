package com.artemchep.keyguard.util.io.spool

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.scratch.withSealedPrivateStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class ConcurrentPositionalReadTest {
    @Test
    fun independentMemoryReadersCanRunOnDifferentThreads() {
        val payload = Random(120).nextBytes(200_019)
        memorySnapshot(payload, listOf(7, 4093), flushEachWrite = true).use { snapshot ->
            runConcurrentReaders { worker ->
                snapshot.openReader().use { reader -> readRanges(payload, worker, reader::readAt) }
            }
        }
    }

    @OptIn(InternalKeyguardIoApi::class)
    @Test
    fun sealedNativeStorageSupportsSimultaneousPositionalReads() {
        val payload = Random(121).nextBytes(600_019)
        withSealedPrivateStorage(payload) { storage ->
            runConcurrentReaders { worker -> readRanges(payload, worker, storage::readAt) }
        }
    }

    private fun readRanges(payload: ByteArray, worker: Int, read: PositionalRead) {
        val random = Random(worker)
        repeat(100) {
            assertPositionalRead(payload, read, random.nextLong(payload.size + 2L), outputSize = 4099)
        }
    }

    private fun runConcurrentReaders(read: (Int) -> Unit) {
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 4).map { worker ->
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "Readers were not released" }
                    read(worker)
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Readers did not start")
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Reader threads did not stop")
        }
    }
}
