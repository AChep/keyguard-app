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
}
