package com.artemchep.keyguard.util.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileRevisionTest {
    @Test
    fun revisionChangesWhenTheFileIsReplaced() {
        val root = Path(SystemTemporaryDirectory, "file-revision-${Random.nextLong()}")
        val file = Path(root, "preferences.json")
        val temp = Path(root, "preferences.json.tmp")
        val path = LocalPath(file.toString())
        SystemFileSystem.createDirectories(root)
        try {
            assertNull(path.fileRevisionOrNull())
            SystemFileSystem.sink(file).buffered().use { it.writeString("first") }
            val first = assertNotNull(path.fileRevisionOrNull())
            assertEquals(first, path.fileRevisionOrNull())

            // Same size, published by a rename like an atomic write does.
            SystemFileSystem.sink(temp).buffered().use { it.writeString("other") }
            SystemFileSystem.atomicMove(temp, file)
            val second = assertNotNull(path.fileRevisionOrNull())
            assertNotEquals(first, second)
            assertEquals(5L, second.size)
        } finally {
            SystemFileSystem.delete(file, mustExist = false)
            SystemFileSystem.delete(temp, mustExist = false)
            SystemFileSystem.delete(root)
        }
    }
}
