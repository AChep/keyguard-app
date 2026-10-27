package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.file.AtomicFileWriteOutcome
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.util.io.spool.copyTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlin.coroutines.CoroutineContext

/** Keeps picker cancellation, failed writes, and completed publication distinct. */
@Suppress("TooGenericExceptionCaught") // Always release staging; cancellation and fatal failures propagate.
internal suspend fun saveAttachmentArchiveExport(
    export: AttachmentPreviewExport,
    fileService: FileService,
    onCleanupFailure: suspend () -> Unit,
    selectDestination: suspend () -> FilePickerResult?,
) {
    var outcome = AttachmentPreviewExportOutcome.Cancelled
    var destination: FilePickerResult? = null
    try {
        val selected = selectDestination() ?: return
        destination = selected
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            export.withSource { source ->
                writeArchiveExport(source, selected, fileService, context)
                // Record publication here: cancellation can discard withContext's return value.
                outcome = AttachmentPreviewExportOutcome.Saved
            }
        }
    } catch (e: Throwable) {
        if (outcome != AttachmentPreviewExportOutcome.Saved && e !is CancellationException) {
            outcome = AttachmentPreviewExportOutcome.Failed
        }
        e.throwIfFatalOrCancellation()
    } finally {
        withContext(NonCancellable) {
            try {
                if (outcome != AttachmentPreviewExportOutcome.Saved) {
                    destination?.let { discardAttachmentArchiveDestination(it, fileService, onCleanupFailure) }
                }
            } finally {
                try {
                    withContext(Dispatchers.IO) { export.close() }
                } catch (e: Throwable) {
                    outcome = AttachmentPreviewExportOutcome.Failed
                    e.throwIfFatalOrCancellation()
                } finally {
                    export.finish(outcome)
                }
            }
        }
    }
}

private fun writeArchiveExport(
    source: Source,
    destination: FilePickerResult,
    fileService: FileService,
    context: CoroutineContext,
) {
    context.ensureActive()
    val uri = destination.uri.toString()
    val result = fileService.atomicWriteToFile(uri) { sink ->
        source.copyTo(sink, checkCancellation = context::ensureActive)
        sink.flush()
        context.ensureActive()
    }
    if (result == AtomicFileWriteOutcome.Unsupported) {
        // Only a document created by this picker may use an in-place write.
        // Never fall back after an exception from the atomic writer.
        if (!destination.created) throw IOException("Destination does not support atomic saving")
        context.ensureActive()
        fileService.writeToFile(uri).use { sink -> source.copyTo(sink, checkCancellation = context::ensureActive) }
    }
}

internal suspend fun discardAttachmentArchiveDestination(
    destination: FilePickerResult,
    fileService: FileService,
    onCleanupFailure: suspend () -> Unit,
) {
    if (!destination.created) return
    withContext(NonCancellable) {
        withContext(Dispatchers.IO) {
            val deleted = runCatchingNonFatal { fileService.delete(destination.uri.toString()) }.getOrDefault(false)
            if (!deleted) onCleanupFailure()
        }
    }
}

/** A late picker result still owns a new document, even if the preview has already closed. */
internal suspend fun selectAttachmentArchiveDestination(
    name: String,
    launchPicker: (FilePickerIntent.NewDocument) -> Unit,
    onAbandoned: (FilePickerResult) -> Unit,
): FilePickerResult? = suspendCancellableCoroutine { continuation ->
    launchPicker(FilePickerIntent.NewDocument(name, writeUriPermission = true) { result ->
        continuation.resume(result) { _, abandoned, _ -> abandoned?.let(onAbandoned) }
    })
}
