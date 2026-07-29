package com.artemchep.keyguard.util.io

import java.io.File
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@Suppress("FunctionNaming")
class FileUriJvmTest {
    @Test
    fun `native file uri round trips through independent Java APIs`() {
        val root = createTempDirectory("file-uri")
        try {
            val path = root
                .resolve("payload # дані_日本語.bin")
                .toAbsolutePath()
            val localPath = path.toLocalPath()

            val uriString = localPath.toFileUriString()
            val uri = URI(uriString)

            assertEquals(path.toFile().toURI().toString(), uriString)
            assertEquals(path, Path.of(uri))
            assertEquals(localPath, uriString.toLocalPathFromFileUriOrNull())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `localhost file uri remains local`() {
        val root = createTempDirectory("file-uri-localhost")
        try {
            val path = root.resolve("payload.bin").toAbsolutePath()
            val uri = path
                .toUri()
                .toString()
                .replaceFirst("file:///", "file://localhost/")

            assertEquals(path.toLocalPath(), uri.toLocalPathFromFileUriOrNull())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Windows drive file uri uses URI path separators`() {
        if (!isWindowsHost()) return
        val root = createTempDirectory("file-uri-windows")
        try {
            val path = root.resolve("payload #.bin").toAbsolutePath()
            val uriString = path.toLocalPath().toFileUriString()
            val uri = URI(uriString)

            assertNull(uri.authority)
            assertFalse(uriString.contains('\\'))
            assertFalse(uriString.contains("%5C", ignoreCase = true))
            assertEquals(path, Path.of(uri))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Windows UNC file uri round trips`() {
        if (!isWindowsHost()) return
        val path = Path.of("""\\server\share\folder\payload.bin""")
        val uri = URI(path.toLocalPath().toFileUriString())

        assertEquals(path.toAbsolutePath(), File(uri).toPath())
        assertEquals(path.toAbsolutePath(), Path.of(uri))
    }

    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
