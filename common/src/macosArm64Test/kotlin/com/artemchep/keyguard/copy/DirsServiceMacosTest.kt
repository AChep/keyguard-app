package com.artemchep.keyguard.copy

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import platform.Foundation.NSFileManager
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.uuid.Uuid

@OptIn(ExperimentalForeignApi::class)
class DirsServiceMacosTest {
    @Test
    fun savesNewFileAndReplacesExistingFile() = withFiles { source, destination ->
        write(source, "first archive")
        DirsServiceMacos.copyItem(url(source), url(destination))
        assertEquals("first archive", read(destination))
        assertPrivate(destination)
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFilePosixPermissions to 420), destination.toString(), null, // 0644
        )
        write(source, "replacement archive")
        DirsServiceMacos.copyItem(url(source), url(destination))
        assertEquals("replacement archive", read(destination))
        assertPrivate(destination)
    }

    @Test
    fun failedCopyPreservesExistingDestination() = withFiles { source, destination ->
        write(destination, "previous archive")
        assertFails { DirsServiceMacos.copyItem(url(source), url(destination)) }
        assertEquals("previous archive", read(destination))
    }

    private fun withFiles(block: (Path, Path) -> Unit) {
        val directory = Path(SystemTemporaryDirectory, "export-save-test-${Uuid.random()}")
        SystemFileSystem.createDirectories(directory)
        val source = Path(directory, "source.zip")
        val destination = Path(directory, "destination.zip")
        try {
            block(source, destination)
        } finally {
            SystemFileSystem.delete(source, mustExist = false)
            SystemFileSystem.delete(destination, mustExist = false)
            SystemFileSystem.delete(directory)
        }
    }

    private fun assertPrivate(path: Path) {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path.toString(), null)
        assertEquals(PRIVATE_FILE_PERMISSIONS, (attributes?.get(NSFilePosixPermissions) as Number).toInt())
    }

    private fun url(path: Path) = NSURL.fileURLWithPath(path.toString())
    private fun write(path: Path, value: String) =
        SystemFileSystem.sink(path).buffered().use { it.writeString(value) }
    private fun read(path: Path) = SystemFileSystem.source(path).buffered().use { it.readString() }
}
