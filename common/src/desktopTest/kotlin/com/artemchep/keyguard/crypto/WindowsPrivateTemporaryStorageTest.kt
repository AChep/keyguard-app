package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.windows.WindowsSecurityAttributes
import com.artemchep.keyguard.platform.windows.ownerOnlySecurityDescriptorSddl
import com.sun.jna.Memory
import com.sun.jna.Platform
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.AclEntryPermission.DELETE
import java.nio.file.attribute.AclEntryPermission.READ_DATA
import java.nio.file.attribute.AclEntryPermission.WRITE_DATA
import java.nio.file.attribute.AclEntryType.ALLOW
import java.nio.file.attribute.AclFileAttributeView
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPrivateTemporaryStorageTest {
    @Test
    fun securityAttributesHaveJnaReadableLayout() {
        Memory(1).use { securityDescriptor ->
            val securityAttributes = WindowsSecurityAttributes(securityDescriptor)

            assertEquals(securityAttributes.size(), securityAttributes.nLength)
            assertEquals(securityDescriptor, securityAttributes.lpSecurityDescriptor)
            assertEquals(0, securityAttributes.bInheritHandle)
        }
    }

    @Test
    fun ownerOnlySddlHasExplicitOwnerAndProtectedDacl() {
        val sid = "S-1-5-21-123-456-789-1001"

        assertEquals(
            "O:${sid}D:P(A;;GA;;;$sid)",
            ownerOnlySecurityDescriptorSddl(sid),
        )
    }

    @Test
    fun namedTemporaryFileUsesOwnerOnlyDacl() {
        if (!Platform.isWindows()) return

        val directory = Files.createTempDirectory("keyguard-windows-private-file-test-").toFile()
        try {
            val file = createWindowsPrivateTemporaryFile(directory)
            try {
                assertTrue(file.name.startsWith("keyguard-private-"))
                val path = file.toPath()
                val owner = Files.getOwner(path)
                val acl = checkNotNull(
                    Files.getFileAttributeView(path, AclFileAttributeView::class.java),
                ).acl

                assertEquals(1, acl.size)
                with(acl.single()) {
                    assertEquals(ALLOW, type())
                    assertEquals(owner, principal())
                    assertTrue(flags().isEmpty())
                    assertTrue(READ_DATA in permissions())
                    assertTrue(WRITE_DATA in permissions())
                    assertTrue(DELETE in permissions())
                }
            } finally {
                file.delete()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun storageIsExclusiveReplayableAndDeletedOnClose() {
        if (!Platform.isWindows()) return

        val directory = Files.createTempDirectory("keyguard-windows-private-storage-test-").toFile()
        try {
            val storage = createWindowsPrivateTemporaryStorage(directory) as WindowsPrivateTemporaryStorage
            val path = storage.path
            try {
                assertTrue(Files.exists(path))
                assertFailsWith<IOException> {
                    FileChannel.open(path, READ).use { }
                }

                val expected = ByteArray(2 * 64 * 1024 + 17) { index ->
                    (index * 31).toByte()
                }
                val sink = storage.sink()
                sink.write(
                    Buffer().apply { write(expected) },
                    expected.size.toLong(),
                )
                storage.sealForReading()

                repeat(2) {
                    val actual = storage.source().buffered().use { source ->
                        source.readByteArray()
                    }
                    assertContentEquals(expected, actual)
                }
            } finally {
                storage.close()
            }

            assertFalse(Files.exists(path))
        } finally {
            directory.deleteRecursively()
        }
    }
}
