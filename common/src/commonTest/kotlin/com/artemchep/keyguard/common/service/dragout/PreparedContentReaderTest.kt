package com.artemchep.keyguard.common.service.dragout

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.test.TestByteSnapshot
import com.artemchep.keyguard.test.testPreparedContentStore
import com.artemchep.keyguard.util.io.spool.ByteSnapshotReader
import com.artemchep.keyguard.util.io.spool.RandomAccessByteSnapshot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedContentReaderTest {
    @Test
    fun `reader admission availability and EOF checks never open backing`() = runTest {
        val store = store(maximumReaders = 1)
        val snapshot = TestByteSnapshot(byteArrayOf(1, 2, 3))
        val content = store.reserve().publish("file", snapshot)
        assertTrue(content.isAvailable())
        val reader = content.openReader()
        assertTrue(content.isAvailable())
        assertFailsWith<IllegalStateException> { content.openReader() }
        assertEquals(0, reader.readAt(0, byteArrayOf()))
        assertEquals(0, reader.readAt(3, ByteArray(4)))
        assertEquals(0, reader.readAt(Long.MAX_VALUE, ByteArray(4)))
        assertEquals(0, snapshot.readerOpens)
        reader.close()
        reader.close()
        content.close()
        runCurrent()
        assertEquals(1, snapshot.closes)
        assertFalse(content.isAvailable())
    }

    @Test
    fun `offset reads fill ranges preserve surrounding bytes and seek without reopening`() = runTest {
        val store = store()
        val bytes = ByteArray(64) { (it * 31 + 7).toByte() }
        val snapshot = TestByteSnapshot(bytes)
        store.reserve().publish("file", snapshot).use { content ->
            content.openReader().use { reader ->
                val range = ByteArray(10) { 0x7f }
                assertEquals(5, reader.readAt(11, range, 2, 7))
                assertContentEquals(bytes.copyOfRange(11, 16), range.copyOfRange(2, 7))
                assertContentEquals(byteArrayOf(0x7f, 0x7f), range.copyOfRange(0, 2))
                assertContentEquals(byteArrayOf(0x7f, 0x7f, 0x7f), range.copyOfRange(7, 10))
                assertEquals(4, reader.readAt(55, range, 0, 4))
                assertContentEquals(bytes.copyOfRange(55, 59), range.copyOfRange(0, 4))
                assertEquals(4, reader.readAt(3, range, 0, 4))
                assertContentEquals(bytes.copyOfRange(3, 7), range.copyOfRange(0, 4))
                assertEquals(2, reader.readAt(62, range, 0, 4))
                assertContentEquals(bytes.copyOfRange(62, 64), range.copyOfRange(0, 2))
                assertEquals(1, snapshot.readerOpens)
            }
        }
        runCurrent()
        assertEquals(1, snapshot.readerCloses)
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `an expired offer cannot open new readers but an existing lazy reader can open and seek`() = runTest {
        val store = store()
        val snapshot = TestByteSnapshot(ByteArray(64) { it.toByte() })
        val content = store.reserve().publish("file", snapshot)
        val reader = content.openReader()
        advanceTimeBy(LIFETIME)
        assertFalse(content.isAvailable())
        assertFailsWith<IllegalStateException> { content.openReader() }
        assertEquals(0, snapshot.readerOpens)
        val output = ByteArray(8)
        assertEquals(8, reader.readAt(48, output))
        assertContentEquals(ByteArray(8) { (48 + it).toByte() }, output)
        content.close()
        runCurrent()
        assertEquals(0, snapshot.closes)
        assertEquals(8, reader.readAt(2, output))
        assertContentEquals(ByteArray(8) { (2 + it).toByte() }, output)
        assertEquals(1, snapshot.readerOpens)
        reader.close()
        runCurrent()
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `invalid ranges do not poison a reader and closed readers reject reads`() = runTest {
        val store = store()
        store.reserve().publish("file", TestByteSnapshot(ByteArray(8) { it.toByte() })).use { content ->
            val reader = content.openReader()
            val data = ByteArray(4)
            assertFailsWith<IllegalArgumentException> { reader.readAt(-1, data) }
            assertFailsWith<IllegalArgumentException> { reader.readAt(0, data, -1, 3) }
            assertFailsWith<IllegalArgumentException> { reader.readAt(0, data, 3, 2) }
            assertFailsWith<IllegalArgumentException> { reader.readAt(0, data, 0, 5) }
            assertEquals(4, reader.readAt(0, data))
            assertContentEquals(byteArrayOf(0, 1, 2, 3), data)
            reader.close()
            assertFailsWith<IllegalStateException> { reader.readAt(0, data) }
        }
    }

    @Test
    fun `failed lazy open poisons reader and releases shared quota once`() = runTest {
        val store = store(maximumReaders = 1)
        val snapshot = TestByteSnapshot(byteArrayOf(7), failOpenAt = 1)
        store.reserve().publish("file", snapshot).use { content ->
            val reader = content.openReader()
            assertFailsWith<IOException> { reader.readAt(0, ByteArray(1)) }
            reader.close()
            reader.close()
            content.openReader().use { replacement ->
                assertEquals(1, replacement.readAt(0, ByteArray(1)))
            }
        }
        runCurrent()
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `a read failure after retirement closes only the existing reader lease`() = runTest {
        val store = store(maximumReaders = 1)
        val snapshot = TestByteSnapshot(ByteArray(8), failReadAt = 2)
        val content = store.reserve().publish("file", snapshot)
        val reader = content.openReader()
        assertEquals(2, reader.readAt(4, ByteArray(2)))
        content.close()
        assertFailsWith<IOException> { reader.readAt(0, ByteArray(2)) }
        reader.close()
        runCurrent()
        assertEquals(1, snapshot.readerCloses)
        assertEquals(1, snapshot.closes)
        store.reserve().close()
    }

    @Test
    fun `unexpected early EOF poisons reader and returns its quota`() = runTest {
        val store = store(maximumReaders = 1)
        val snapshot = TestByteSnapshot(byteArrayOf(1, 2), size = 4)
        store.reserve().publish("file", snapshot).use { content ->
            val reader = content.openReader()
            assertFailsWith<IOException> { reader.readAt(0, ByteArray(4)) }
            assertFailsWith<IllegalStateException> { reader.readAt(0, ByteArray(1)) }
            reader.close()
            content.openReader().close()
        }
        runCurrent()
        assertEquals(1, snapshot.readerCloses)
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `failed source close releases lease once and reports only a redacted message`() = runTest {
        val log = RecordingLogRepository()
        val store = store(maximumReaders = 1, log = log)
        val snapshot = TestByteSnapshot(byteArrayOf(1), failReaderClose = true)
        store.reserve().publish("file", snapshot).use { content ->
            val reader = content.openReader()
            assertEquals(1, reader.readAt(0, ByteArray(1)))
            assertFailsWith<IOException> { reader.close() }
            reader.close()
            content.openReader().close()
        }
        runCurrent()
        assertEquals(listOf("Failed to close prepared content storage"), log.messages)
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `fatal or cancellation cleanup failures are not hidden by a read error`() = runTest {
        listOf(AssertionError("fatal cleanup"), CancellationException("cancelled cleanup")).forEach { expected ->
            val store = store(maximumReaders = 1)
            var readerCloses = 0
            val snapshot = object : RandomAccessByteSnapshot {
                override val size = 1L
                override fun openReader(): ByteSnapshotReader = object : ByteSnapshotReader {
                    override fun readAt(position: Long, data: ByteArray, startIndex: Int, endIndex: Int): Int =
                        throw IOException("read failed")

                    override fun close() {
                        readerCloses += 1
                        throw expected
                    }
                }

                override fun close() = Unit
            }
            store.reserve().publish("file", snapshot).use { content ->
                val reader = content.openReader()
                val actual = assertFailsWith<Throwable> { reader.readAt(0, ByteArray(1)) }
                assertSame(expected, actual)
                assertTrue(actual.suppressedExceptions.single() is IOException)
                reader.close()
                content.openReader().close()
                assertEquals(1, readerCloses)
            }
        }
    }

    private fun TestScope.store(
        maximumReaders: Int = 8,
        log: LogRepository = LogRepositoryBridge(emptyList()),
    ) = testPreparedContentStore(
        limits = PreparedContentLimits(offerLifetimeMillis = LIFETIME, maximumReaders = maximumReaders),
        log = log,
    )

    private companion object {
        const val LIFETIME = 100L
    }
}
