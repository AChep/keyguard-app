package com.artemchep.keyguard.util.io

import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.ReplacementAccessPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Backticked test names describe behavior more clearly than production-style identifiers.
@Suppress("FunctionNaming")
class LocalPathIoTest {
    @Test
    fun `writeText and readText round trip utf8 text`() {
        val root = createTempDirectory("local-path-io-text")
        val file = root.resolve("payload.txt").toLocalPath()
        val data = "payload Привіт"

        file.writeText(data, options = localPathTestOptions)

        assertEquals(data, file.readText())
    }

    @Test
    fun `writeBytes and readBytes round trip bytes`() {
        val root = createTempDirectory("local-path-io-bytes")
        val file = root.resolve("payload.bin").toLocalPath()
        val data = byteArrayOf(0x00, 0x01, 0x7F, 0x40, 0x7E)

        file.writeBytes(data, options = localPathTestOptions)

        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `read helpers fail on missing file`() {
        val root = createTempDirectory("local-path-io-missing")
        val file = root.resolve("missing.bin").toLocalPath()

        assertFailsWith<FileNotFoundException> {
            file.readText()
        }
        assertFailsWith<FileNotFoundException> {
            file.readBytes()
        }
    }

    @Test
    fun `source streams the file content`() {
        val root = createTempDirectory("local-path-io-source")
        val file = root.resolve("payload.bin").toLocalPath()
        val data = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(data, options = localPathTestOptions)

        val streamed = file.source().buffered().use { source ->
            source.readByteArray()
        }

        assertContentEquals(data, streamed)
    }

    @Test
    fun `delete removes a file and tolerates a missing one`() {
        val root = createTempDirectory("local-path-io-delete")
        val file = root.resolve("payload.bin").toLocalPath()
        file.writeBytes(byteArrayOf(1), options = localPathTestOptions)

        file.delete()
        assertFailsWith<FileNotFoundException> { file.readBytes() }

        // A second delete of the now-missing path is not an error...
        file.delete()
        // ...unless the caller explicitly requires existence.
        assertFailsWith<kotlinx.io.files.FileNotFoundException> {
            file.delete(mustExist = true)
        }
    }
}

private val localPathTestOptions = AtomicWriteOptions(
    publication = AtomicPublicationPolicy.Replace(
        access = ReplacementAccessPolicy.UseRequestedPermissions(
            permissions = AtomicFilePermissions.ProcessDefault,
        ),
    ),
    parentDirectories = ParentDirectoryPolicy.RequireExisting,
    existingParentLinks = ExistingParentLinkPolicy.FollowAndPin,
    synchronization = SynchronizationPolicy.Required(
        SyncLevel.FileSynchronized,
    ),
)
