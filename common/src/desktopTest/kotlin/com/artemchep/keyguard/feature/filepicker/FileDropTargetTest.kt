package com.artemchep.keyguard.feature.filepicker

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragData
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class FileDropTargetTest {
    @Test
    fun `returns all regular files from dropped files list in order`() {
        val directory = createTempDirectory()
        val firstFile = createTempFile(directory = directory, suffix = ".txt").also {
            it.writeText("first")
        }
        val secondFile = createTempFile(directory = directory, suffix = ".txt").also {
            it.writeText("second")
        }

        val result = droppedFileResults(
            dragData = FakeFilesList(
                files = listOf(
                    secondFile.toUri().toString(),
                    directory.toUri().toString(),
                    firstFile.toUri().toString(),
                ),
            ),
        )

        assertEquals(
            listOf(secondFile.toUri().toString(), firstFile.toUri().toString()),
            result.map { it.uri.toString() },
        )
        assertEquals(
            listOf(secondFile.fileName.toString(), firstFile.fileName.toString()),
            result.map { it.name },
        )
        assertEquals(listOf(6L, 5L), result.map { it.size })
    }

    @Test
    fun `detects file list drag without reading files`() {
        var filesRead = false

        val result = isFileDragData(
            dragData = object : DragData.FilesList {
                override fun readFiles(): List<String> {
                    filesRead = true
                    error("boom")
                }
            },
        )

        assertTrue(result)
        assertFalse(filesRead)
    }

    @Test
    fun `skips malformed unsupported and non-file entries`() {
        val directory = createTempDirectory()
        val file = createTempFile(directory = directory, suffix = ".txt").also {
            it.writeText("file")
        }

        val result = droppedFileResults(
            fileUris = listOf(
                "not a uri",
                "https://example.com/file.txt",
                directory.toUri().toString(),
                file.toUri().toString(),
            ),
        )

        assertEquals(listOf(file.toUri().toString()), result.map { it.uri.toString() })
    }

    @Test
    fun `stops after limit regular files`() {
        val directory = createTempDirectory()
        val files = List(3) {
            createTempFile(directory = directory, suffix = ".txt")
        }

        val result = droppedFileResults(
            fileUris = listOf(directory.toUri().toString()) +
                files.map { it.toUri().toString() },
            limit = 2,
        )

        assertEquals(
            files.take(2).map { it.toUri().toString() },
            result.map { it.uri.toString() },
        )
    }

    @Test
    fun `ignores missing file uri`() {
        val directory = createTempDirectory()
        val result = droppedFileResults(
            fileUris = listOf(
                directory.resolve("missing.txt").toUri().toString(),
            ),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores unsupported dropped data`() {
        val result = droppedFileResults(
            dragData = object : DragData {},
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores unreadable dropped file list`() {
        val result = droppedFileResults(
            dragData = object : DragData.FilesList {
                override fun readFiles(): List<String> {
                    error("boom")
                }
            },
        )

        assertTrue(result.isEmpty())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class FakeFilesList(
    private val files: List<String>,
) : DragData.FilesList {
    override fun readFiles(): List<String> = files
}
