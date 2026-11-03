package com.artemchep.keyguard.common.service.dragout

import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.test.TestByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteSnapshotReader
import com.artemchep.keyguard.util.io.spool.RandomAccessByteSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

class PreparedContentReaderConcurrencyTest {
    @Test
    fun `seekable close waits for its read and releases the backing exactly once`() {
        val reading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val inRead = AtomicBoolean()
        val sourceCloses = AtomicInteger()
        val snapshotCloses = AtomicInteger()
        val snapshot = object : RandomAccessByteSnapshot {
            override val size = 1L
            override fun openReader(): ByteSnapshotReader = object : ByteSnapshotReader {
                override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int {
                    inRead.set(true)
                    reading.countDown()
                    try {
                        check(proceed.await(5, TimeUnit.SECONDS))
                        data[startIndex] = 7
                        return 1
                    } finally {
                        inRead.set(false)
                    }
                }

                override fun close() {
                    assertFalse(inRead.get())
                    sourceCloses.incrementAndGet()
                }
            }

            override fun close() {
                snapshotCloses.incrementAndGet()
            }
        }
        val store = store()
        val content = store.reserve().publish("file", snapshot)
        val reader = content.openReader()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val read = executor.submit<Int> { reader.readAt(0, ByteArray(1)) }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            content.close()
            val closeStarted = CountDownLatch(1)
            val close = executor.submit {
                closeStarted.countDown()
                reader.close()
            }
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            assertFalse(close.isDone)
            assertEquals(0, sourceCloses.get())
            assertEquals(0, snapshotCloses.get())
            proceed.countDown()
            assertEquals(1, read.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            reader.close()
            assertEquals(1, sourceCloses.get())
            assertEquals(1, snapshotCloses.get())
        } finally {
            proceed.countDown()
            executor.shutdownNow()
            reader.close()
            store.close()
        }
    }

    @Test
    fun `a lazy reader open retains its lease across concurrent offer and store closure`() {
        val opening = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closes = AtomicInteger()
        val bytes = ByteArray(32) { it.toByte() }
        val backing = TestByteSnapshot(bytes)
        val snapshot = object : RandomAccessByteSnapshot {
            override val size = bytes.size.toLong()
            override fun openReader(): ByteSnapshotReader {
                opening.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                assertEquals(0, closes.get())
                return backing.openReader()
            }

            override fun close() {
                closes.incrementAndGet()
            }
        }
        val store = store()
        val content = store.reserve().publish("file", snapshot)
        val reader = content.openReader()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val output = ByteArray(4)
            val backwards = executor.submit<Int> { reader.readAt(0, output) }
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            content.close()
            store.close()
            assertFalse(content.isAvailable())
            assertFailsWith<IllegalStateException> { content.openReader() }
            assertEquals(0, closes.get())
            proceed.countDown()
            assertEquals(4, backwards.get(5, TimeUnit.SECONDS))
            assertContentEquals(bytes.copyOfRange(0, 4), output)
            reader.close()
            assertEquals(1, closes.get())
        } finally {
            proceed.countDown()
            executor.shutdownNow()
            reader.close()
            store.close()
        }
    }

    @Test
    fun `simultaneous independent readers receive their own full cursor`() {
        val bytes = ByteArray(64 * 1024) { (it * 31 + 7).toByte() }
        val snapshot = TestByteSnapshot(bytes)
        val store = store()
        val content = store.reserve().publish("file", snapshot)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reads = List(2) { executor.submit<ByteArray> { content.readAllBytes() } }
            reads.forEach { assertContentEquals(bytes, it.get(5, TimeUnit.SECONDS)) }
        } finally {
            executor.shutdownNow()
            content.close()
            store.close()
        }
    }

    private fun store(): PreparedContentStore = PreparedContentStore(
        scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        logRepository = LogRepositoryBridge(emptyList()),
        cleanupDispatcher = Dispatchers.Unconfined,
    )
}
