package com.artemchep.keyguard.common.service.file

import com.artemchep.keyguard.common.io.readByteArrayAndClose
import com.artemchep.keyguard.common.io.writeByteArray
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PureFileServiceTest {
    private val service = PureFileService()

    @Test
    fun `exists returns true for an existing file uri`() {
        val root = createTempDirectory("file-service-exists")
        val file = root.resolve("payload.bin")
        file.writeBytes("payload".encodeToByteArray())

        assertTrue(service.exists(file.toUri().toString()))
    }

    @Test
    fun `exists returns false for a missing file uri`() {
        val root = createTempDirectory("file-service-missing")
        val file = root.resolve("missing.bin")

        assertFalse(service.exists(file.toUri().toString()))
    }

    @Test
    fun `readFromFile reads bytes from disk`() {
        val root = createTempDirectory("file-service-read")
        val file = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()
        file.writeBytes(data)

        val actual = service.readFromFile(file.toUri().toString()).readByteArrayAndClose()

        assertContentEquals(data, actual)
    }

    @Test
    fun `writeToFile writes bytes to disk`() {
        val root = createTempDirectory("file-service-write")
        val file = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()

        service.writeToFile(file.toUri().toString()).use { sink ->
            sink.writeByteArray(data)
        }

        assertTrue(file.exists())
        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `escaped file uri path resolves correctly`() {
        val root = createTempDirectory("file-service-space")
        val file = root.resolve("payload with spaces.bin")
        val data = "payload".encodeToByteArray()
        file.writeBytes(data)

        val actual = service.readFromFile(file.toUri().toString()).readByteArrayAndClose()

        assertContentEquals(data, actual)
    }

    @Test
    fun `file uri with authority resolves to correct path`() {
        val root = createTempDirectory("file-service-authority")
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
}
