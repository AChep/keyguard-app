package com.artemchep.keyguard.util.io

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Backticked test names describe behavior more clearly than production-style identifiers.
@Suppress("FunctionNaming")
class FileUriTest {
    @Test
    fun `encodes reserved path characters and round trips`() {
        val path = testPath("keyguard files", "#hash?.txt")

        val uri = path.toFileUriString()

        assertTrue(uri.startsWith("file:"))
        assertFalse(uri.contains(' '))
        assertFalse(uri.substringAfter("file:").contains('#'))
        assertFalse(uri.substringAfter("file:").contains('?'))
        assertEquals(path, uri.toLocalPathFromFileUriOrNull())
    }

    @Test
    fun `unicode path round trips`() {
        val path = testPath("keyguard", "дані_日本語-._~.txt")

        val uri = path.toFileUriString()

        assertEquals(path, uri.toLocalPathFromFileUriOrNull())
    }

    @Test
    fun `non-file uri is rejected`() {
        assertNull("https://example.com/file.txt".toLocalPathFromFileUriOrNull())
    }

    @Test
    fun `query and fragment do not become part of the file path`() {
        val path = testPath("keyguard", "payload.bin")
        val uri = path.toFileUriString() + "?download=true#preview"

        assertEquals(path, uri.toLocalPathFromFileUriOrNull())
    }

    private fun testPath(
        vararg parts: String,
    ): LocalPath = LocalPath(
        Path(SystemTemporaryDirectory, *parts).toString(),
    )
}
