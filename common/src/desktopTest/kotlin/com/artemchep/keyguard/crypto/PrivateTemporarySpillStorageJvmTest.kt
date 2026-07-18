package com.artemchep.keyguard.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PrivateTemporarySpillStorageJvmTest {
    @Test
    fun payloadRoundTripsAndCanBeReadMoreThanOnce() {
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val snapshot = PrivateTemporarySpillStorage(TestPrivateTemporaryStorage()).use { writer ->
            writer.sink().use { sink -> sink.write(payload) }
            writer.seal()
        }

        snapshot.use {
            assertEquals(payload.size.toLong(), snapshot.size)
            assertContentEquals(payload, snapshot.readBytes())
            assertContentEquals(payload, snapshot.readBytes())
        }
    }

    @Test
    fun closingTheWriterAfterSealDoesNotCloseTheSnapshotStorage() {
        val storage = TestPrivateTemporaryStorage()
        val writer = PrivateTemporarySpillStorage(storage)
        writer.sink().use { sink -> sink.write(byteArrayOf(1)) }
        val snapshot = writer.seal()
        writer.close()

        assertEquals(0, storage.closeCount)
        snapshot.use {
            assertContentEquals(byteArrayOf(1), snapshot.readBytes())
        }
        assertEquals(1, storage.closeCount)
    }

    @Test
    fun closeDiscardsBufferedBytesWithoutWritingThem() {
        val storage = TestPrivateTemporaryStorage()
        val writer = PrivateTemporarySpillStorage(storage)
        writer.sink().write(byteArrayOf(1, 2, 3))

        writer.close()

        assertEquals(0, storage.storedByteCount)
        assertEquals(1, storage.closeCount)
    }
}
