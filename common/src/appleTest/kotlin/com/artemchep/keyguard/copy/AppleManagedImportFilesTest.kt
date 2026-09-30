package com.artemchep.keyguard.copy

import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toFileUriString
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleManagedImportFilesTest {
    private val managedRoot = LocalPath(NSTemporaryDirectory()).resolve("keyguard-import")

    private val unmanagedRoot = LocalPath(NSTemporaryDirectory())
        .resolve("managed-import-test-" + NSUUID().UUIDString)

    @OptIn(ExperimentalForeignApi::class)
    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(unmanagedRoot.value, null)
        AppleManagedImportFiles.clear()
    }

    @Test
    fun deletesManagedCopyAndItsDirectory() {
        val directory = managedRoot.resolve(NSUUID().UUIDString)
        val file = directory.writeFile("secret.txt")

        assertTrue(AppleManagedImportFiles.deleteIfManaged(file.toFileUriString()))
        assertFalse(file.exists())
        assertFalse(directory.exists())
    }

    @Test
    fun keepsFileOutsideManagedDirectory() {
        val file = unmanagedRoot.resolve(NSUUID().UUIDString).writeFile("original.txt")

        assertFalse(AppleManagedImportFiles.deleteIfManaged(file.toFileUriString()))
        assertTrue(file.exists())
    }

    @Test
    fun keepsFileEscapingManagedDirectory() {
        val file = unmanagedRoot.resolve("uuid").writeFile("original.txt")
        // Exists, so the `..` segments really resolve out of the managed directory.
        SystemFileSystem.createDirectories(managedRoot.resolve("uuid").toKotlinxIoPath())
        val escaping = "file://" + managedRoot.value + "/uuid/../../" +
            unmanagedRoot.value.substringAfterLast('/') + "/uuid/original.txt"

        assertFalse(AppleManagedImportFiles.deleteIfManaged(escaping))
        assertTrue(file.exists())
    }

    @Test
    fun keepsNestedFileInManagedDirectory() {
        val file = managedRoot.resolve(NSUUID().UUIDString, "nested").writeFile("file.txt")

        assertFalse(AppleManagedImportFiles.deleteIfManaged(file.toFileUriString()))
        assertTrue(file.exists())
    }

    @Test
    fun keepsManagedDirectoryItself() {
        val directory = managedRoot.resolve(NSUUID().UUIDString)
        directory.writeFile("file.txt")

        assertFalse(AppleManagedImportFiles.deleteIfManaged(directory.toFileUriString()))
        assertTrue(directory.exists())
    }

    @Test
    fun clearDeletesEveryManagedCopy() {
        val first = managedRoot.resolve(NSUUID().UUIDString).writeFile("a.txt")
        val second = managedRoot.resolve(NSUUID().UUIDString).writeFile("b.txt")

        AppleManagedImportFiles.clear()

        assertFalse(first.exists())
        assertFalse(second.exists())
        assertFalse(managedRoot.exists())
    }
}

private fun LocalPath.writeFile(name: String): LocalPath {
    SystemFileSystem.createDirectories(toKotlinxIoPath())
    val file = resolve(name)
    SystemFileSystem.sink(file.toKotlinxIoPath()).buffered().use { sink ->
        sink.write("plaintext".encodeToByteArray())
    }
    return file
}

private fun LocalPath.exists(): Boolean =
    SystemFileSystem.exists(toKotlinxIoPath())
