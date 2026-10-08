package com.artemchep.keyguard.util.io

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalPathReadTextIfExistsTest {
    @Test
    fun onlyMissingFilesReturnNull() {
        val root = Path(SystemTemporaryDirectory, "read-if-exists-${Random.nextLong()}")
        val file = Path(root, "preferences.json")
        SystemFileSystem.createDirectories(root)
        try {
            assertNull(LocalPath(file.toString()).readTextIfExists())
            SystemFileSystem.sink(file).buffered().use { it.writeString("Привіт") }
            assertEquals("Привіт", LocalPath(file.toString()).readTextIfExists())
            assertFailsWith<IOException> { LocalPath(root.toString()).readTextIfExists() }
        } finally {
            SystemFileSystem.delete(file, mustExist = false)
            SystemFileSystem.delete(root)
        }
    }
}
