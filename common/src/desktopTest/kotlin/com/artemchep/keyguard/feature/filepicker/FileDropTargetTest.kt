package com.artemchep.keyguard.feature.filepicker

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragData
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class FileDropTargetTest {
    @Test
    fun `returns first regular file from dropped files list`() {
        val directory = createTempDirectory()
        val firstFile = createTempFile(directory = directory, suffix = ".txt").also {
            it.writeText("first")
        }
        createTempFile(directory = directory, suffix = ".txt").also {
            it.writeText("second")
        }

        val result = droppedFileResult(
            dragData = FakeFilesList(
                files = listOf(
                    directory.toUri().toString(),
                    firstFile.toUri().toString(),
                ),
            ),
        )

        assertEquals(firstFile.toUri().toString(), result?.uri?.toString())
        assertEquals(firstFile.fileName.toString(), result?.name)
        assertEquals(5L, result?.size)
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

        val result = droppedFileResult(
            fileUris = listOf(
                "not a uri",
                "https://example.com/file.txt",
                directory.toUri().toString(),
                file.toUri().toString(),
            ),
        )

        assertEquals(file.toUri().toString(), result?.uri?.toString())
    }

    @Test
    fun `ignores missing file uri`() {
        val directory = createTempDirectory()
        val result = droppedFileResult(
            fileUris = listOf(
                directory.resolve("missing.txt").toUri().toString(),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `ignores unsupported dropped data`() {
        val result = droppedFileResult(
            dragData = object : DragData {},
        )

        assertNull(result)
    }

    @Test
    fun `ignores unreadable dropped file list`() {
        val result = droppedFileResult(
            dragData = object : DragData.FilesList {
                override fun readFiles(): List<String> {
                    error("boom")
                }
            },
        )

        assertNull(result)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class FakeFilesList(
    private val files: List<String>,
) : DragData.FilesList {
    override fun readFiles(): List<String> = files
}
