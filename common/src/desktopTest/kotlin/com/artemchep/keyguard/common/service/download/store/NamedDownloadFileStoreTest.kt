package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.download.writeBytes
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission.DELETE
import java.nio.file.attribute.AclEntryPermission.READ_DATA
import java.nio.file.attribute.AclEntryPermission.WRITE_DATA
import java.nio.file.attribute.AclEntryType.ALLOW
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class NamedDownloadFileStoreTest {
    @Test
    fun legacyCacheHasNamedHandoffAndBothDeleteWithDownload() = withRoot { root ->
        val store = store(root)
        val legacy = root.resolve("downloads/id.bin").apply { parentFile.mkdirs() }
        val bytes = "synthetic attachment".encodeToByteArray()
        legacy.writeBytes(bytes)
        val info = info("id", "attachment.txt")
        assertTrue(store.exists(info))
        val file = File(URI(store.completedUri(info, legacy.toURI().toString())))
        assertEquals(root.resolve("downloads/id/attachment.txt"), file)
        assertContentEquals(bytes, file.readBytes())
        assertOwnerOnlyPermissions(file.toPath())
        assertTrue(legacy.exists())
        assertTrue(store.delete(info))
        assertFalse(file.parentFile.exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun failedHandoffKeepsTheLegacyCache() = withRoot { root ->
        val legacy = root.resolve("downloads/id.bin").apply { parentFile.mkdirs() }
        legacy.writeText("original")
        root.resolve("downloads/id").writeText("blocks directory creation")
        assertFails { store(root).uri(info("id", "data.txt")) }
        assertEquals("original", legacy.readText())
    }

    @Test
    fun identicalNamesRemainIsolatedByDownloadId() = withRoot { root ->
        val store = store(root)
        val first = info("first", "data.txt")
        val second = info("second", "data.txt")
        store.writer(first).writeBytes(byteArrayOf(1))
        store.writer(second).writeBytes(byteArrayOf(2))
        assertContentEquals(byteArrayOf(1), File(URI(store.uri(first))).readBytes())
        assertContentEquals(byteArrayOf(2), File(URI(store.uri(second))).readBytes())
    }

    @Test
    fun renamedAttachmentRefreshesHandoffAndRemovesOldName() = withRoot { root ->
        val store = store(root)
        val original = info("id", "old.txt")
        store.writer(original).writeBytes(byteArrayOf(3))
        val old = File(URI(store.uri(original)))
        val renamed = original.copy(name = "new.txt")
        val current = File(URI(store.uri(renamed)))
        assertFalse(old.exists())
        assertContentEquals(byteArrayOf(3), current.readBytes())
        assertTrue(store.exists(renamed))
        assertTrue(store.delete(renamed))
        assertFalse(current.exists())
        assertFalse(root.resolve("downloads/id.bin").exists())
    }

    @Test
    fun namesAreSingleBoundedComponentsWithTheirExtensionPreserved() {
        assertEquals("attachment", downloadDisplayFileName(".."))
        assertEquals("_secret_file.txt", downloadDisplayFileName("../secret:file.txt"))
        val name = downloadDisplayFileName("語".repeat(300) + ".txt")
        assertTrue(name.encodeToByteArray().size <= 200)
        assertTrue(name.endsWith(".txt"))
        assertEquals("notes.txt", downloadDisplayFileName("notes.txt"))
    }

    private fun withRoot(block: suspend (File) -> Unit) = runTest {
        val root = createTempDirectory("named-download").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertOwnerOnlyPermissions(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                posix.readAttributes().permissions(),
            )
        } else {
            val acl = assertNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java)).acl
            assertEquals(1, acl.size)
            with(acl.single()) {
                assertEquals(ALLOW, type())
                assertEquals(Files.getOwner(path), principal())
                assertTrue(flags().isEmpty())
                assertTrue(READ_DATA in permissions())
                assertTrue(WRITE_DATA in permissions())
                assertTrue(DELETE in permissions())
            }
        }
    }

    private fun store(root: File) = NamedDownloadFileStore {
        AtomicDirectoryDestination(root.toPath().toLocalPath(), AtomicRelativePath.parse("downloads"))
    }

    private fun info(id: String, name: String) = DownloadInfoEntity(
        id = id, localCipherId = "cipher", remoteCipherId = null,
        attachmentId = id, url = "https://example.com/file", urlIsOneTime = false,
        name = name, createdDate = Instant.DISTANT_PAST,
    )
}
