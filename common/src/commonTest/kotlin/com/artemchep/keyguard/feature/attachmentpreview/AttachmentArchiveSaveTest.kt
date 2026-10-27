@file:OptIn(
    com.artemchep.keyguard.util.io.InternalKeyguardIoApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentArchiveSaveTest {
    @Test
    fun successfulNewDocumentIsKeptAndStagingIsReleased() = runTest {
        val fixture = Fixture()
        fixture.save()
        assertEquals(32_768, fixture.service.output.size)
        assertFalse(fixture.service.deleted)
        fixture.assertFinished(AttachmentPreviewExportOutcome.Saved)
    }

    @Test
    fun cancellationBetweenChunksDeletesTheNewDocument() = runTest {
        val fixture = Fixture()
        val task = launch {
            val job = currentCoroutineContext().job
            fixture.storage.onRead = { if (it > 8192) job.cancel() }
            fixture.save()
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(fixture.service.output.size in 1..<32_768)
        assertTrue(fixture.service.deleted)
        fixture.assertFinished(AttachmentPreviewExportOutcome.Cancelled)
    }

    @Test
    fun failedReadDeletesTheNewDocument() = runTest {
        val fixture = Fixture()
        fixture.storage.onRead = { if (it > 8192) throw IOException("Read failed") }
        fixture.save()
        assertTrue(fixture.service.deleted)
        fixture.assertFinished(AttachmentPreviewExportOutcome.Failed)
    }

    @Test
    fun failedSinkCloseDoesNotCountAsSuccessfulPublication() = runTest {
        val fixture = Fixture()
        fixture.service.failOnClose = true
        fixture.save()
        assertTrue(fixture.service.deleted)
        fixture.assertFinished(AttachmentPreviewExportOutcome.Failed)
    }

    @Test
    fun cancellationBeforeIoStillDeletesTheSelectedDocument() = runTest {
        val fixture = Fixture()
        val task = launch {
            saveAttachmentArchiveExport(fixture.export, fixture.service, fixture::cleanupFailed) {
                currentCoroutineContext().job.cancel()
                destination()
            }
        }
        task.join()
        assertFalse(fixture.service.opened)
        assertTrue(fixture.service.deleted)
        fixture.assertFinished(AttachmentPreviewExportOutcome.Cancelled)
    }

    @Test
    fun refusedDeletionIsReportedEvenAfterCancellation() = runTest {
        val fixture = Fixture()
        fixture.service.canDelete = false
        val task = launch {
            val job = currentCoroutineContext().job
            fixture.storage.onRead = { job.cancel() }
            fixture.save()
        }
        task.join()
        assertEquals(1, fixture.cleanupFailures)
        fixture.assertFinished(AttachmentPreviewExportOutcome.Cancelled)
    }

    @Test
    fun unsupportedExistingDestinationIsNeverOpenedOrDeleted() = runTest {
        val fixture = Fixture()
        fixture.save(destination(created = false))
        assertFalse(fixture.service.opened)
        assertFalse(fixture.service.deleted)
        fixture.assertFinished(AttachmentPreviewExportOutcome.Failed)
    }

    @Test
    fun dismissingThePickerReleasesStagingWithoutAnError() = runTest {
        val fixture = Fixture()
        fixture.save(null)
        assertFalse(fixture.service.opened)
        assertFalse(fixture.service.deleted)
        assertEquals(0, fixture.cleanupFailures)
        fixture.assertFinished(AttachmentPreviewExportOutcome.Cancelled)
    }

    @Test
    fun latePickerResultIsHandedToCleanup() = runTest {
        var intent: FilePickerIntent.NewDocument? = null
        val abandoned = mutableListOf<FilePickerResult>()
        val task = launch {
            selectAttachmentArchiveDestination("file.bin", { intent = it }, abandoned::add)
        }
        runCurrent()
        task.cancelAndJoin()
        val selected = destination()
        intent!!.onResult(selected)
        assertEquals(listOf(selected), abandoned)
    }

    @Test
    fun cancellationWhileDeliveringPickerResultAlsoHandsItToCleanup() = runTest {
        var intent: FilePickerIntent.NewDocument? = null
        val abandoned = mutableListOf<FilePickerResult>()
        val task = launch {
            selectAttachmentArchiveDestination("file.bin", { intent = it }, abandoned::add)
        }
        runCurrent()
        val selected = destination()
        intent!!.onResult(selected)
        task.cancelAndJoin()
        assertEquals(listOf(selected), abandoned)
    }

    private class Fixture {
        val storage = ArchiveExportTestStorage()
        val service = DocumentFileService()
        private val outcomes = mutableListOf<AttachmentPreviewExportOutcome>()
        val export = AttachmentPreviewExport("file.bin", storage, outcomes::add)
        var cleanupFailures = 0

        suspend fun save(selected: FilePickerResult? = destination()) {
            saveAttachmentArchiveExport(export, service, ::cleanupFailed) { selected }
        }

        suspend fun cleanupFailed() {
            currentCoroutineContext().ensureActive()
            cleanupFailures++
        }

        fun assertFinished(outcome: AttachmentPreviewExportOutcome) {
            assertEquals(listOf(outcome), outcomes)
            assertTrue(storage.closed)
        }
    }
}

private fun destination(created: Boolean = true) = FilePickerResult(
    uri = leParseUri("content://documents/document/new-file"),
    name = "file.bin",
    size = null,
    created = created,
)

private class DocumentFileService : FileService {
    val output = Buffer()
    var opened = false
    var deleted = false
    var canDelete = true
    var failOnClose = false
    override fun exists(uri: String) = !deleted
    override fun readFromFile(uri: String): Source = error("Not used")
    override fun writeToFile(uri: String): Sink {
        opened = true
        return object : RawSink {
            override fun write(source: Buffer, byteCount: Long) { output.write(source, byteCount) }
            override fun flush() = Unit
            override fun close() { if (failOnClose) throw IOException("Close failed") }
        }.buffered()
    }
    override fun delete(uri: String): Boolean {
        deleted = canDelete
        return canDelete
    }
}
