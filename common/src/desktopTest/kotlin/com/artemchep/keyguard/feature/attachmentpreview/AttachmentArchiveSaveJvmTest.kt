@file:OptIn(com.artemchep.keyguard.util.io.InternalKeyguardIoApi::class)

package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.service.file.AtomicFileWriteOutcome
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.Sink
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentArchiveSaveJvmTest {
    @Test
    fun successfulSaveAtomicallyReplacesTheExistingFile() = runTest {
        withDestination { root, path ->
            val data = ByteArray(32_768) { it.toByte() }
            val fixture = Fixture(data)
            fixture.save(path, FileServiceImpl())
            assertContentEquals(data, path.readBytes())
            fixture.assertFinished(AttachmentPreviewExportOutcome.Saved)
            assertEquals(listOf(path), root.listDirectoryEntries())
        }
    }

    @Test
    fun cancellationDuringCopyPreservesTheExistingFileAndRemovesStaging() = runTest {
        withDestination { root, path ->
            val fixture = Fixture()
            val task = launch {
                val job = currentCoroutineContext().job
                fixture.storage.onRead = { if (it > 8192) job.cancel() }
                fixture.save(path, FileServiceImpl())
            }
            task.join()
            assertTrue(task.isCancelled)
            assertContentEquals(original, path.readBytes())
            fixture.assertFinished(AttachmentPreviewExportOutcome.Cancelled)
            assertEquals(listOf(path), root.listDirectoryEntries())
        }
    }

    @Test
    fun failedAtomicWritePreservesTheOriginalAndNeverFallsBackToTruncation() = runTest {
        withDestination { root, path ->
            val fixture = Fixture()
            val local = FileServiceImpl()
            val failing = object : FileService by local {
                override fun atomicWriteToFile(
                    uri: String,
                    accessToken: FileAccessToken?,
                    write: (Sink) -> Unit,
                ): AtomicFileWriteOutcome = local.atomicWriteToFile(uri, accessToken) { sink ->
                    write(sink)
                    throw IOException("Publication failed")
                }
                override fun writeToFile(uri: String): Sink = error("Must not fall back to an in-place write")
            }
            fixture.save(path, failing)
            assertContentEquals(original, path.readBytes())
            fixture.assertFinished(AttachmentPreviewExportOutcome.Failed)
            assertEquals(listOf(path), root.listDirectoryEntries())
        }
    }

    @Test
    fun cancellationAfterPublicationDoesNotDeleteOrMisreportTheSavedFile() = runTest {
        withDestination { root, path ->
            val data = ByteArray(32_768) { it.toByte() }
            val fixture = Fixture(data)
            val local = FileServiceImpl()
            val task = launch {
                val job = currentCoroutineContext().job
                val service = object : FileService by local {
                    override fun atomicWriteToFile(
                        uri: String,
                        accessToken: FileAccessToken?,
                        write: (Sink) -> Unit,
                    ): AtomicFileWriteOutcome = local.atomicWriteToFile(uri, accessToken, write).also {
                        job.cancel()
                    }
                }
                // Even a destination owned by this request must survive cancellation after publication.
                fixture.save(path, service, created = true)
            }
            task.join()
            assertTrue(task.isCancelled)
            assertContentEquals(data, path.readBytes())
            fixture.assertFinished(AttachmentPreviewExportOutcome.Saved)
            assertEquals(listOf(path), root.listDirectoryEntries())
        }
    }

    private suspend fun withDestination(block: suspend (Path, Path) -> Unit) {
        val root = createTempDirectory("archive-save")
        try {
            val destination = root.resolve("file.bin")
            destination.writeBytes(original)
            block(root, destination)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(data: ByteArray = ByteArray(32_768)) {
        val storage = ArchiveExportTestStorage(data)
        private val outcomes = mutableListOf<AttachmentPreviewExportOutcome>()
        private val export = AttachmentPreviewExport("file.bin", storage, outcomes::add)

        suspend fun save(path: Path, service: FileService, created: Boolean = false) {
            saveAttachmentArchiveExport(export, service, { error("Unexpected cleanup failure") }) {
                FilePickerResult(leParseUri(path.toUri().toString()), "file.bin", null, created = created)
            }
        }

        fun assertFinished(outcome: AttachmentPreviewExportOutcome) {
            assertEquals(listOf(outcome), outcomes)
            assertTrue(storage.closed)
        }
    }

    companion object {
        private val original = "existing file contents".encodeToByteArray()
    }
}
