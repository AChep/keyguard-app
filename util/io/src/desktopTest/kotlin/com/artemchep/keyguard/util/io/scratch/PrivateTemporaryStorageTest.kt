package com.artemchep.keyguard.util.io.scratch

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(InternalKeyguardIoApi::class)
class PrivateTemporaryStorageTest {
    @Test
    fun storageIsPathlessAndEnforcesWriteSealReadLifecycle() {
        val directory = createTempDirectory("keyguard-private-storage")
        try {
            createPrivateTemporaryStorage(directory.toLocalPath()).use { storage ->
                if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                    assertTrue(Files.list(directory).use { entries -> entries.findAny().isEmpty })
                }
                val sink = storage.sink()
                assertFailsWith<IllegalStateException> { storage.sink() }
                assertFailsWith<IllegalStateException> { storage.source() }

                val expected = byteArrayOf(1, 2, 3)
                sink.write(Buffer().apply { write(expected) }, expected.size.toLong())
                storage.sealForReading()

                repeat(2) {
                    val actual = storage.source().buffered().use { source ->
                        source.readByteArray()
                    }
                    assertContentEquals(expected, actual)
                }
            }
            assertTrue(directory.toFile().list().orEmpty().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun positionalReadsSpanNativeChunks() {
        val directory = createTempDirectory("keyguard-private-storage")
        try {
            createPrivateTemporaryStorage(directory.toLocalPath()).use { storage ->
                val expected = ByteArray(600_000) { (it % 251).toByte() }
                storage.sink().write(Buffer().apply { write(expected) }, expected.size.toLong())
                assertFailsWith<IllegalStateException> { storage.readAt(0, ByteArray(1)) }
                storage.sealForReading()

                val output = ByteArray(expected.size)
                assertEquals(expected.size, storage.readAt(0, output))
                assertContentEquals(expected, output)
                val tail = ByteArray(10) { 0x5a }
                assertEquals(4, storage.readAt(expected.size - 4L, tail, 3, 10))
                val expectedTail = ByteArray(10) { 0x5a }
                expected.copyInto(expectedTail, 3, expected.size - 4, expected.size)
                assertContentEquals(expectedTail, tail)
                assertEquals(0, storage.readAt(expected.size.toLong(), tail))
                assertEquals(0, storage.readAt(Long.MAX_VALUE, tail))
                assertEquals(0, storage.readAt(0, tail, 3, 3))
                assertFailsWith<IllegalArgumentException> { storage.readAt(-1, tail) }
                assertFailsWith<IllegalArgumentException> { storage.readAt(0, tail, 8, 3) }
                assertFailsWith<IllegalArgumentException> { storage.readAt(0, tail, 8, 11) }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
