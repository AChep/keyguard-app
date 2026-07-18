package com.artemchep.keyguard.common.service.file

import com.artemchep.keyguard.util.foundation.io.readByteArrayAndClose
import com.artemchep.keyguard.util.foundation.io.writeByteArray
import kotlinx.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileServiceImplTest {
    private val service = FileServiceImpl()

    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanUpTempDirs() {
        tempDirs.forEach { dir -> dir.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(prefix: String): Path =
        createTempDirectory(prefix).also(tempDirs::add)

    @Test
    fun `exists returns true for an existing file uri`() {
        val root = tempDir("file-service-exists")
        val file = root.resolve("payload.bin")
        file.writeBytes("payload".encodeToByteArray())

        assertTrue(service.exists(file.toUri().toString()))
    }

    @Test
    fun `exists returns false for a missing file uri`() {
        val root = tempDir("file-service-missing")
        val file = root.resolve("missing.bin")

        assertFalse(service.exists(file.toUri().toString()))
    }

    @Test
    fun `readFromFile reads bytes from disk`() {
        val root = tempDir("file-service-read")
        val file = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()
        file.writeBytes(data)

        val actual = service.readFromFile(file.toUri().toString()).readByteArrayAndClose()

        assertContentEquals(data, actual)
    }

    @Test
    fun `writeToFile writes bytes to disk`() {
        val root = tempDir("file-service-write")
        val file = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()

        service.writeToFile(file.toUri().toString()).use { sink ->
            sink.writeByteArray(data)
        }

        assertTrue(file.exists())
        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `atomicWriteToFile replaces bytes and cleans up temp sibling`() {
        val root = tempDir("file-service-atomic-write")
        val file = root.resolve("payload.bin")
        file.writeBytes("old".encodeToByteArray())
        val data = "new".encodeToByteArray()

        val result = service.atomicWriteToFile(
            uri = file.toUri().toString(),
            accessToken = null,
            bytes = data,
        )

        assertTrue(result)
        assertContentEquals(data, file.readBytes())
        assertNoTempSiblings(root)
    }

    @Test
    fun `streaming atomicWriteToFile replaces bytes and cleans up temp sibling`() {
        val root = tempDir("file-service-streaming-atomic-write")
        val file = root.resolve("payload.bin")
        file.writeBytes("old".encodeToByteArray())
        val data = ByteArray(128 * 1024 + 17) { index -> (index * 31).toByte() }

        val result = service.atomicWriteToFile(
            uri = file.toUri().toString(),
            accessToken = null,
            write = { sink -> sink.writeByteArray(data) },
        )

        assertTrue(result)
        assertContentEquals(data, file.readBytes())
        assertNoTempSiblings(root)
    }

    @Test
    fun `streaming atomicWriteToFile keeps destination and cleans up temp sibling when write fails`() {
        val root = tempDir("file-service-streaming-atomic-write-throw")
        val file = root.resolve("payload.bin")
        val old = "old".encodeToByteArray()
        file.writeBytes(old)

        assertFailsWith<IllegalStateException> {
            service.atomicWriteToFile(
                uri = file.toUri().toString(),
                accessToken = null,
                write = { error("Simulated write failure") },
            )
        }

        assertContentEquals(old, file.readBytes())
        assertNoTempSiblings(root)
    }

    @Test
    fun `streaming atomicWriteToFile throws when the destination cannot be replaced`() {
        val root = tempDir("file-service-streaming-atomic-write-move")
        // A non-empty directory cannot be renamed over, which makes the
        // staging write succeed and the atomic move fail.
        val destination = root.resolve("payload.bin")
        destination.createDirectory()
        val child = destination.resolve("child.bin")
        child.writeBytes("child".encodeToByteArray())
        val destinationUri = destination.toUri().toString().trimEnd('/')

        assertFailsWith<IOException> {
            service.atomicWriteToFile(
                uri = destinationUri,
                accessToken = null,
                write = { sink -> sink.writeByteArray("new".encodeToByteArray()) },
            )
        }

        assertContentEquals("child".encodeToByteArray(), child.readBytes())
        assertNoTempSiblings(root)
    }

    @Test
    fun `streaming atomicWriteToFile refuses non-atomic destinations without invoking write`() {
        var invoked = false

        val result = service.atomicWriteToFile(
            uri = "content://documents/document/1",
            accessToken = null,
            write = { invoked = true },
        )

        assertFalse(result)
        assertFalse(invoked)
    }

    @Test
    fun `escaped file uri path resolves correctly`() {
        val root = tempDir("file-service-space")
        val file = root.resolve("payload with spaces.bin")
        val data = "payload".encodeToByteArray()
        file.writeBytes(data)

        val actual = service.readFromFile(file.toUri().toString()).readByteArrayAndClose()

        assertContentEquals(data, actual)
    }

    @Test
    fun `file uri with authority resolves to correct path`() {
        val root = tempDir("file-service-authority")
        val file = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()
        file.writeBytes(data)

        // Build a URI with a "localhost" authority: file://localhost/absolute/path
        val standardUri = file.toUri().toString() // file:///absolute/path
        val authorityUri = standardUri.replaceFirst("file:///", "file://localhost/")

        val actual = service.readFromFile(authorityUri).readByteArrayAndClose()

        assertContentEquals(data, actual)
    }

    @Test
    fun `unsupported uri scheme fails consistently`() {
        val existsError =
            assertFailsWith<IllegalStateException> {
                service.exists("content://documents/document/1")
            }
        val readError =
            assertFailsWith<IllegalStateException> {
                service.readFromFile("content://documents/document/1")
            }
        val writeError =
            assertFailsWith<IllegalStateException> {
                service.writeToFile("content://documents/document/1")
            }

        assertEquals(
            "Unsupported URI protocol, could not read from 'content://documents/document/1'.",
            existsError.message,
        )
        assertEquals(
            "Unsupported URI protocol, could not read from 'content://documents/document/1'.",
            readError.message,
        )
        assertEquals(
            "Unsupported URI protocol, could not write to 'content://documents/document/1'.",
            writeError.message,
        )
    }

    private fun assertNoTempSiblings(root: Path) {
        assertTrue(
            root.toFile()
                .walkTopDown()
                .none { it.name.endsWith(".kgtmp") },
        )
    }
}
